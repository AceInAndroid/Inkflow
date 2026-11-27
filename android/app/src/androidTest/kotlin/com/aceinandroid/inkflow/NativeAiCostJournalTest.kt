package com.aceinandroid.inkflow

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aceinandroid.inkflow.ai.*
import com.aceinandroid.inkflow.core.AiChatMessage
import com.aceinandroid.inkflow.core.AiRole
import com.aceinandroid.inkflow.core.ReadingContextSnapshot
import com.aceinandroid.inkflow.core.ai.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class NativeAiCostJournalTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferenceFiles = Collections.synchronizedSet(mutableSetOf<String>())
    private inner class LocalContext : ContextWrapper(base) {
        private val prefix = "cost-journal-${UUID.randomUUID()}"
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val file = "$prefix-$name"
            preferenceFiles += file
            return base.getSharedPreferences(file, mode)
        }
    }
    @After fun cleanUp() { preferenceFiles.toList().forEach(base::deleteSharedPreferences) }
    private fun prefs(context: Context) = context.getSharedPreferences("native_ai_workspace", Context.MODE_PRIVATE)
    private fun day() = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
    private fun request(id: String = "request.1", book: Long? = 42, workload: String = "reading.chat") =
        AiRequest(id, workload, ReadingContextSnapshot("private book body", "private selection", "private title"),
            "private prompt", bookId = book, userAuthorized = true)
    private fun result(estimated: Boolean = false, provider: String = "actual.provider", attempts: Int = 1) =
        AiRequestResult(provider, "shared-model", AiTokenUsage(10, 2, estimated), attempts)
    private fun legacy(input: Long = 20, output: Long = 3, estimated: Boolean = false) = JSONObject()
        .put("${day()}|shared-model", JSONObject().put("input", input).put("output", output).put("estimated", estimated)).toString()
    private fun snapshot(context: Context) = NativeAiCostRepository(context).read()
    private fun journal(context: Context) = JSONObject(prefs(context).getString("usage_journal_v2", null)!!)
    private fun write(context: Context, id: String = "request.1", estimated: Boolean = false) =
        NativeAiStore(context).recordUsage(request(id), result(estimated))

    @Test fun firstAppendFreezesLegacyBeforeUpdatingUiAndNeverCountsTheGrowingAggregate() {
        val context = LocalContext()
        val old = legacy()
        prefs(context).edit().putString("token_usage", old).commit()
        assertEquals(23L, snapshot(context).summarize().totals.tokens.total)
        assertTrue(write(context))
        assertEquals(old, prefs(context).getString("legacy_usage_snapshot", null))
        assertEquals(35L, snapshot(context).summarize().totals.tokens.total)
        assertEquals(30L, JSONObject(prefs(context).getString("token_usage", null)!!)
            .getJSONObject("${day()}|shared-model").getLong("input"))
        assertTrue(write(context, "request.2", estimated = true))
        val totals = snapshot(context).summarize().totals
        assertEquals(35L, totals.reported.total)
        assertEquals(12L, totals.estimated.total)
        assertEquals(old, prefs(context).getString("legacy_usage_snapshot", null))
        assertNull(totals.requestCount) // The frozen legacy row has no request count.
        assertEquals(2L, totals.knownRequestCount)
        prefs(context).edit().putString("token_usage", legacy(999, 999)).commit()
        assertEquals(47L, snapshot(context).summarize().totals.tokens.total)
    }

    @Test fun readingOldUnmigratedUsageDoesNotWritePreferencesOrInventLinks() {
        val context = LocalContext()
        prefs(context).edit().putString("token_usage", legacy(estimated = true)).commit()
        val before = prefs(context).all
        val view = snapshot(context)
        assertEquals(before, prefs(context).all)
        assertEquals(23L, view.summarize().totals.mixedOrUnknown.total)
        assertTrue(view.records.all { it.attribution.providerId == null && it.attribution.bookId == null })
    }

    @Test fun freshJournalSeparatesReportedAndEstimatedWithExactRequestCounts() {
        val context = LocalContext()
        assertTrue(write(context, "reported"))
        assertTrue(write(context, "estimated", estimated = true))
        val view = snapshot(context)
        val totals = view.summarize().totals
        assertEquals(12L, totals.reported.total)
        assertEquals(12L, totals.estimated.total)
        assertEquals(0L, totals.mixedOrUnknown.total)
        assertEquals(2L, totals.requestCount)
        assertFalse(NativeAiCostGap.PROVIDER_LINK_UNAVAILABLE in view.gaps)
        assertFalse(NativeAiCostGap.REQUEST_COUNT_UNAVAILABLE in view.gaps)
    }

    @Test fun actualResultProviderRegisteredRoleAndExplicitTaskAreAttributedAcrossAllDimensions() {
        val context = LocalContext()
        val task = request("task.1:chunk", workload = "reading.wiki").copy(generationAuthorization =
            GenerationAuthorization(42, "task.1", GenerationScope.READ_BOUNDARY, 0.0, listOf("chapter"), "a".repeat(64), true))
        assertTrue(NativeAiStore(context).recordUsage(task, result(provider = "fallback.actual", attempts = 3)))
        assertTrue(NativeAiStore(context).recordUsage(request("translate", 99, "reading.translate"),
            result(estimated = true, provider = "other.actual")))
        val view = snapshot(context)
        val first = view.records.single { it.attribution.requestId == task.id }
        assertEquals("fallback.actual", first.attribution.providerId)
        assertEquals(AiProviderRole.CONVERSATION, first.attribution.role)
        assertEquals("task.1", first.attribution.taskId)
        assertEquals(1L, first.requestCount) // Attempts must not become requests or multiply tokens.
        val second = view.records.single { it.attribution.requestId == "translate" }
        assertEquals(AiProviderRole.LIGHTWEIGHT, second.attribution.role)
        assertNull(second.attribution.taskId)
        for (dimension in listOf(AiCostDimension.BOOK, AiCostDimension.TASK, AiCostDimension.REQUEST,
                AiCostDimension.PROVIDER, AiCostDimension.ROLE, AiCostDimension.WORKLOAD)) {
            val grouped = view.summarize(setOf(dimension))
            assertEquals(dimension.name, 2, grouped.groups.size)
            assertEquals(24L, grouped.groups.sumOf { it.totals.tokens.total })
        }
        assertEquals(1, view.summarize(setOf(AiCostDimension.MODEL)).groups.size)
        assertEquals(2L, view.summarize().totals.requestCount)
        assertTrue(NativeAiCostGap.RETRY_USAGE_UNAVAILABLE in view.gaps)
        assertTrue(NativeAiCostGap.FAILED_REQUEST_USAGE_UNAVAILABLE in view.gaps)
    }

    @Test fun duplicateRequestAcrossNewStoreInstancesDoesNotUpdateJournalOrUiTwice() {
        val context = LocalContext()
        assertTrue(write(context))
        val before = prefs(context).all
        assertTrue(NativeAiStore(context).recordUsage(request(), result(estimated = true, provider = "changed")))
        assertEquals(before, prefs(context).all)
        assertEquals(1, journal(context).getJSONArray("records").length())
        assertEquals(12L, snapshot(context).summarize().totals.tokens.total)
    }

    @Test fun concurrentStoresFreezeOnceAndDeduplicateByRequestId() {
        val context = LocalContext()
        prefs(context).edit().putString("token_usage", legacy()).commit()
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until 32).map { i -> pool.submit<Boolean> {
                start.await()
                write(context, if (i % 2 == 0) "shared" else "unique.$i")
            } }
            start.countDown()
            futures.forEach { assertTrue(it.get(30, TimeUnit.SECONDS)) }
        } finally { pool.shutdownNow() }
        assertEquals(17, journal(context).getJSONArray("records").length())
        assertEquals(23L + 17 * 12, snapshot(context).summarize().totals.tokens.total)
        assertEquals(20L, JSONObject(prefs(context).getString("legacy_usage_snapshot", null)!!)
            .getJSONObject("${day()}|shared-model").getLong("input"))
    }

    @Test fun journalNeverContainsPromptSnapshotHistoryCredentialsOrBackupEntries() {
        val context = LocalContext()
        prefs(context).edit().putString("api_key", "private credential")
            .putString("providers_v1", "private provider configuration").commit()
        assertTrue(NativeAiStore(context).recordUsage(request().copy(history = listOf(
            AiChatMessage("history.1", AiRole.USER, "private history"))), result()))
        val raw = prefs(context).getString("usage_journal_v2", null)!!
        for (secret in listOf("private prompt", "private book body", "private selection", "private title",
                "private history", "private credential", "private provider configuration")) assertFalse(raw.contains(secret))
        val row = journal(context).getJSONArray("records").getJSONObject(0)
        assertEquals(setOf("requestId", "bookId", "workload", "providerId", "role", "model", "day", "time",
            "input", "output", "estimated", "attempts"), row.keys().asSequence().toSet())
        val backup = NativeSettingsBackup.capture(context).toString()
        for (key in listOf("usage_journal_v2", "legacy_usage_snapshot", "token_usage", "usage_accounting_incomplete", "private"))
            assertFalse(backup.contains(key))
    }

    @Test fun unknownFutureJournalIsPreservedAndCannotFallBackToLiveAggregate() {
        val context = LocalContext()
        val future = """{"version":99,"future":"private opaque data"}"""
        prefs(context).edit().putString("usage_journal_v2", future).putString("legacy_usage_snapshot", legacy())
            .putString("token_usage", legacy(999, 999)).commit()
        assertFalse(write(context))
        assertEquals(future, prefs(context).getString("usage_journal_v2", null))
        val view = snapshot(context)
        assertEquals(23L, view.summarize().totals.tokens.total)
        assertTrue(NativeAiCostGap.JOURNAL_UNREADABLE in view.gaps)
        assertTrue(NativeAiCostGap.ACCOUNTING_WRITE_FAILED in view.gaps)
        assertFalse(view.toString().contains("private opaque"))
    }

    @Test fun corruptAggregateDoesNotThrowOrFreezeAnInvalidMigration() {
        val context = LocalContext()
        prefs(context).edit().putString("token_usage", "private broken payload").commit()
        assertFalse(write(context))
        assertFalse(prefs(context).contains("legacy_usage_snapshot"))
        assertFalse(prefs(context).contains("usage_journal_v2"))
        assertTrue(NativeAiCostGap.ACCOUNTING_WRITE_FAILED in snapshot(context).gaps)
        assertFalse(snapshot(context).toString().contains("private broken"))
    }

    @Test fun applyFailureIsContainedAndLaterRetryCanRecordTheSameRequest() {
        val local = LocalContext()
        val fail = AtomicBoolean(true)
        val context = object : ContextWrapper(local) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val delegate = local.getSharedPreferences(name, mode)
                if (name != "native_ai_workspace") return delegate
                return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
                    arrayOf(SharedPreferences::class.java)) { _, method, args ->
                    if (method.name == "edit") {
                        val editor = delegate.edit()
                        Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                            arrayOf(SharedPreferences.Editor::class.java)) { proxy, action, values ->
                            if (action.name == "apply" && fail.compareAndSet(true, false)) error("private disk fault")
                            val result = try { action.invoke(editor, *(values ?: emptyArray())) }
                                catch (e: InvocationTargetException) { throw e.targetException }
                            if (result === editor) proxy else result
                        }
                    } else try { method.invoke(delegate, *(args ?: emptyArray())) }
                        catch (e: InvocationTargetException) { throw e.targetException }
                } as SharedPreferences
            }
        }
        assertFalse(write(context))
        assertTrue(NativeAiCostGap.ACCOUNTING_WRITE_FAILED in snapshot(local).gaps)
        assertTrue(write(context))
        assertEquals(12L, snapshot(local).summarize().totals.tokens.total)
        assertFalse(snapshot(local).toString().contains("private disk fault"))
    }

    @Test fun incompleteMigrationNeverRefreezesOrReadsTheLiveUiAggregate() {
        for (missingBaseline in listOf(false, true)) {
            val context = LocalContext()
            assertTrue(write(context))
            val edit = prefs(context).edit().putString("token_usage", legacy(999, 999))
            if (missingBaseline) edit.remove("legacy_usage_snapshot") else edit.remove("usage_journal_v2")
            edit.commit()
            assertFalse(write(context, "new"))
            val view = snapshot(context)
            assertEquals(if (missingBaseline) 12L else 0L, view.summarize().totals.tokens.total)
            assertTrue((if (missingBaseline) NativeAiCostGap.LEGACY_BASELINE_UNAVAILABLE else NativeAiCostGap.JOURNAL_UNREADABLE) in view.gaps)
        }
    }

    @Test fun recordLimitEvictsOldestButRetainsDedupForAnEvictedRequest() {
        val context = LocalContext()
        assertTrue(write(context, "oldest"))
        val root = journal(context)
        val prototype = root.getJSONArray("records").getJSONObject(0)
        val rows = JSONArray()
        for (i in 0 until 2048) rows.put(JSONObject(prototype.toString()).put("requestId", if (i == 0) "oldest" else "seed.$i"))
        root.put("records", rows)
        prefs(context).edit().putString("usage_journal_v2", root.toString()).commit()
        assertTrue(write(context, "new"))
        assertEquals(2048, journal(context).getJSONArray("records").length())
        val view = snapshot(context)
        assertTrue(NativeAiCostGap.JOURNAL_RETENTION_LIMIT in view.gaps)
        assertTrue(NativeAiCostGap.IDEMPOTENCY_WINDOW_LIMITED in view.gaps)
        assertEquals(2048L, view.summarize().totals.requestCount)
        assertTrue(write(context, "oldest"))
        assertEquals(2048, journal(context).getJSONArray("records").length())
    }

    @Test fun utf8ByteBudgetEvictsRowsAndNeverExceedsTwoMiB() {
        val context = LocalContext()
        assertTrue(write(context, "seed"))
        val root = journal(context)
        val row = root.getJSONArray("records").getJSONObject(0)
            .put("requestId", "r".repeat(512)).put("providerId", "p".repeat(512))
            .put("model", "模".repeat(512)).put("taskId", "t".repeat(512))
        root.put("records", JSONArray())
        val bytesPerRow = row.toString().toByteArray(Charsets.UTF_8).size + 1
        val count = (2 * 1024 * 1024 - root.toString().toByteArray(Charsets.UTF_8).size) / bytesPerRow
        val rows = JSONArray()
        for (i in 0 until count) rows.put(JSONObject(row.toString())
            .put("requestId", "r".repeat(500) + i.toString().padStart(12, '0')))
        root.put("records", rows)
        assertTrue(root.toString().toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024)
        prefs(context).edit().putString("usage_journal_v2", root.toString()).commit()
        val next = request("new".repeat(170), workload = "reading.wiki").copy(generationAuthorization =
            GenerationAuthorization(42, "t".repeat(512), GenerationScope.READ_BOUNDARY, 0.0,
                listOf("chapter"), "a".repeat(64), true))
        assertTrue(NativeAiStore(context).recordUsage(next, result(provider = "p".repeat(512)).copy(model = "模".repeat(512))))
        val raw = prefs(context).getString("usage_journal_v2", null)!!
        assertTrue(raw.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024)
        assertTrue(JSONObject(raw).getJSONArray("records").length() <= count)
        assertTrue(NativeAiCostGap.JOURNAL_RETENTION_LIMIT in snapshot(context).gaps)
    }

    @Test fun requestDigestRetentionIsBoundedAndExposesItsIdempotencyWindow() {
        val context = LocalContext()
        assertTrue(write(context, "seed"))
        val root = journal(context)
        val hashes = JSONObject()
        val now = System.currentTimeMillis()
        for (i in 0 until 8192) hashes.put(i.toString(16).padStart(64, '0'), now)
        root.put("seen", hashes)
        prefs(context).edit().putString("usage_journal_v2", root.toString()).commit()
        assertTrue(write(context, "next"))
        assertEquals(8192, journal(context).getJSONObject("seen").length())
        assertTrue(NativeAiCostGap.IDEMPOTENCY_WINDOW_LIMITED in snapshot(context).gaps)
    }

    @Test fun agedJournalRowsAreExcludedOnReadAndPrunedOnAppendWithAnExplicitGap() {
        val context = LocalContext()
        assertTrue(write(context, "aged"))
        val root = journal(context)
        root.getJSONArray("records").getJSONObject(0).put("time", System.currentTimeMillis() - 367L * 86400000)
        prefs(context).edit().putString("usage_journal_v2", root.toString()).commit()
        assertTrue(snapshot(context).records.isEmpty())
        assertTrue(NativeAiCostGap.JOURNAL_RETENTION_LIMIT in snapshot(context).gaps)
        assertTrue(write(context, "fresh"))
        assertEquals(1, journal(context).getJSONArray("records").length())
        assertEquals(12L, snapshot(context).summarize().totals.tokens.total)
    }

    @Test fun duplicateAndMalformedJournalRowsDoNotDoubleCountOrExposeUnknownFields() {
        val context = LocalContext()
        assertTrue(write(context))
        val root = journal(context)
        val row = root.getJSONArray("records").getJSONObject(0)
        root.getJSONArray("records").put(JSONObject(row.toString()).put("prompt", "private injected body"))
            .put(JSONObject(row.toString()).put("requestId", "bad").put("input", -1))
        prefs(context).edit().putString("usage_journal_v2", root.toString()).commit()
        val view = snapshot(context)
        assertEquals(12L, view.summarize().totals.tokens.total)
        assertEquals(2, view.skippedRows)
        assertTrue(NativeAiCostGap.INVALID_ROWS_SKIPPED in view.gaps)
        assertFalse(view.toString().contains("private injected"))
    }
}
