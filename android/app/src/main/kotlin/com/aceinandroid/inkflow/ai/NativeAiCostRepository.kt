package com.aceinandroid.inkflow.ai

import android.content.Context
import com.aceinandroid.inkflow.core.ai.*
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Coverage limits are returned alongside totals; tokens are never manufactured to fill a gap. */
enum class NativeAiCostGap {
    BOOK_LINK_UNAVAILABLE, TASK_LINK_UNAVAILABLE, REQUEST_LINK_UNAVAILABLE,
    PROVIDER_LINK_UNAVAILABLE, ROLE_LINK_UNAVAILABLE, WORKLOAD_LINK_UNAVAILABLE,
    REQUEST_COUNT_UNAVAILABLE, PRICING_UNAVAILABLE, RETAINED_SUCCESS_USAGE_ONLY,
    LEGACY_ESTIMATE_SPLIT_UNAVAILABLE, USAGE_QUALITY_UNAVAILABLE,
    INVALID_ROWS_SKIPPED, STORAGE_UNREADABLE,
    FAILED_REQUEST_USAGE_UNAVAILABLE, RETRY_USAGE_UNAVAILABLE,
    JOURNAL_RETENTION_LIMIT, IDEMPOTENCY_WINDOW_LIMITED, ACCOUNTING_WRITE_FAILED,
    JOURNAL_UNREADABLE, LEGACY_BASELINE_UNAVAILABLE,
}

data class NativeAiCostSnapshot(
    val records: List<AiCostRecord>,
    val gaps: Set<NativeAiCostGap>,
    val skippedRows: Int = 0,
) {
    fun summarize(dimensions: Set<AiCostDimension> = emptySet()): AiCostSummary =
        AiCostSummarizer.summarize(records, dimensions)
}

/**
 * Read-only local projection. Before migration read token_usage; after migration read ONLY the
 * frozen legacy baseline plus v2 journal. Never load a provider registry, credentials or bodies.
 * Call read off the UI thread. Missing/corrupt migration pieces never trigger aggregate fallback.
 */
class NativeAiCostRepository(context: Context) {
    private val appContext = context.applicationContext

    fun read(): NativeAiCostSnapshot = synchronized(NativeAiUsageJournal.lock) {
        try {
            val prefs = appContext.getSharedPreferences("native_ai_workspace", Context.MODE_PRIVATE)
            val journal = prefs.getString(NativeAiUsageJournal.KEY, null)
            val baseline = prefs.getString(NativeAiUsageJournal.LEGACY_KEY, null)
            val legacy = if (journal == null && baseline == null) prefs.getString("token_usage", "{}") ?: "{}"
                else baseline
            val old = legacy?.let(::decode) ?: NativeAiCostSnapshot(emptyList(),
                baseGaps + NativeAiCostGap.LEGACY_BASELINE_UNAVAILABLE)
            val records = old.records.toMutableList()
            val gaps = old.gaps.toMutableSet()
            var skipped = old.skippedRows
            if (journal != null) {
                gaps += NativeAiCostGap.IDEMPOTENCY_WINDOW_LIMITED
                val root = runCatching { NativeAiUsageJournal.parse(journal) }.getOrNull()
                if (root == null) gaps += NativeAiCostGap.JOURNAL_UNREADABLE
                else {
                    if (root.getBoolean("truncated")) gaps += NativeAiCostGap.JOURNAL_RETENTION_LIMIT
                    val rows = root.getJSONArray("records")
                    val seen = mutableSetOf<String>()
                    val cutoff = System.currentTimeMillis() - NativeAiUsageJournal.MAX_AGE_MS
                    for (i in 0 until rows.length()) {
                        val row = rows.optJSONObject(i)
                        val record = row?.let(NativeAiUsageJournal::record)
                        if (record == null || !seen.add(record.attribution.requestId!!)) { skipped++; continue }
                        if (NativeAiUsageJournal.count(row.opt("time"))!! < cutoff) {
                            gaps += NativeAiCostGap.JOURNAL_RETENTION_LIMIT
                            continue
                        }
                        records += record
                        if (NativeAiUsageJournal.count(row.opt("attempts"))!! > 1) gaps += NativeAiCostGap.RETRY_USAGE_UNAVAILABLE
                    }
                }
            } else if (baseline != null) gaps += NativeAiCostGap.JOURNAL_UNREADABLE
            if (prefs.getBoolean(NativeAiUsageJournal.INCOMPLETE_KEY, false)) gaps += NativeAiCostGap.ACCOUNTING_WRITE_FAILED
            if (skipped > 0) gaps += NativeAiCostGap.INVALID_ROWS_SKIPPED
            gaps += coverage(records)
            NativeAiCostSnapshot(records.toList(), gaps.toSet(), skipped)
        } catch (_: Exception) { unreadable() }
    }

    companion object {
        private val baseGaps = setOf(NativeAiCostGap.PRICING_UNAVAILABLE,
            NativeAiCostGap.RETAINED_SUCCESS_USAGE_ONLY, NativeAiCostGap.FAILED_REQUEST_USAGE_UNAVAILABLE,
            NativeAiCostGap.RETRY_USAGE_UNAVAILABLE)

        /** Legacy OR-merged estimated flags cannot be split back into reported/estimated portions. */
        internal fun decode(raw: String): NativeAiCostSnapshot {
            val all = runCatching { NativeAiUsageJournal.objectValue(raw) }.getOrElse { return unreadable() }
            val rows = mutableListOf<AiCostRecord>()
            val gaps = baseGaps.toMutableSet()
            var skipped = 0
            for (key in all.keys().asSequence().sorted()) {
                val separator = key.indexOf('|')
                val day = if (separator == 10) key.substring(0, separator) else ""
                val model = if (separator == 10) key.substring(separator + 1) else ""
                val row = all.optJSONObject(key)
                val input = NativeAiUsageJournal.count(row?.opt("input"))
                val output = NativeAiUsageJournal.count(row?.opt("output"))
                if (!NativeAiUsageJournal.validDay(day) || model.isBlank() || model.any { it.isISOControl() } ||
                    row == null || input == null || output == null || input > Long.MAX_VALUE - output) {
                    skipped++; continue
                }
                val quality = when (row.opt("estimated")) {
                    false -> AiCostUsageKind.REPORTED
                    true -> {
                        gaps += NativeAiCostGap.LEGACY_ESTIMATE_SPLIT_UNAVAILABLE
                        AiCostUsageKind.MIXED_OR_UNKNOWN
                    }
                    else -> {
                        gaps += NativeAiCostGap.USAGE_QUALITY_UNAVAILABLE
                        AiCostUsageKind.MIXED_OR_UNKNOWN
                    }
                }
                rows += AiCostRecord(AiCostTokens(input, output), quality, AiCostAttribution(model = model, day = day))
            }
            if (skipped > 0) gaps += NativeAiCostGap.INVALID_ROWS_SKIPPED
            return NativeAiCostSnapshot(rows.toList(), gaps + coverage(rows), skipped)
        }

        private fun coverage(rows: List<AiCostRecord>): Set<NativeAiCostGap> = buildSet {
            fun missing(gap: NativeAiCostGap, check: (AiCostAttribution) -> Boolean) {
                if (rows.any { check(it.attribution) }) add(gap)
            }
            missing(NativeAiCostGap.BOOK_LINK_UNAVAILABLE) { it.bookId == null }
            missing(NativeAiCostGap.TASK_LINK_UNAVAILABLE) { it.taskId == null }
            missing(NativeAiCostGap.REQUEST_LINK_UNAVAILABLE) { it.requestId == null }
            missing(NativeAiCostGap.PROVIDER_LINK_UNAVAILABLE) { it.providerId == null }
            missing(NativeAiCostGap.ROLE_LINK_UNAVAILABLE) { it.role == null }
            missing(NativeAiCostGap.WORKLOAD_LINK_UNAVAILABLE) { it.workload == null }
            if (rows.any { it.requestCount == null }) add(NativeAiCostGap.REQUEST_COUNT_UNAVAILABLE)
        }
        private fun unreadable() = NativeAiCostSnapshot(emptyList(), baseGaps + NativeAiCostGap.STORAGE_UNREADABLE)
    }
}

/** Shared storage contract and process lock; pure JSON/metadata validation, no preference writes. */
internal object NativeAiUsageJournal {
    val lock = Any()
    const val KEY = "usage_journal_v2"
    const val LEGACY_KEY = "legacy_usage_snapshot"
    const val INCOMPLETE_KEY = "usage_accounting_incomplete"
    const val MAX_RECORDS = 2048
    const val MAX_SEEN = 8192
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_AGE_MS = 366L * 86400000
    private val fields = listOf("requestId", "bookId", "taskId", "workload", "providerId", "role",
        "model", "day", "time", "input", "output", "estimated", "attempts")

    fun empty() = JSONObject().put("version", 2).put("records", JSONArray()).put("seen", JSONObject())
        .put("truncated", false)

    fun objectValue(raw: String): JSONObject {
        val parser = JSONTokener(raw)
        val value = parser.nextValue()
        require(value is JSONObject && parser.nextClean() == '\u0000') { "Invalid usage object" }
        return value
    }

    fun parse(raw: String): JSONObject = objectValue(raw).also {
        require(count(it.opt("version")) == 2L && it.optJSONArray("records") != null &&
            it.optJSONObject("seen") != null && it.opt("truncated") is Boolean) { "Invalid usage journal" }
        require(it.getJSONArray("records").length() <= MAX_RECORDS && it.getJSONObject("seen").length() <= MAX_SEEN &&
            raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Usage journal exceeds retention limits" }
    }

    fun count(value: Any?): Long? = when (value) {
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }?.takeIf { it >= 0 }

    private fun text(row: JSONObject, key: String): String? = (row.opt(key) as? String)
        ?.takeIf { it.isNotBlank() && it.length <= 512 && it.none(Char::isISOControl) }

    fun record(row: JSONObject): AiCostRecord? = runCatching {
        val id = text(row, "requestId") ?: return null
        val provider = text(row, "providerId") ?: return null
        val model = text(row, "model") ?: return null
        val workload = text(row, "workload") ?: return null
        val role = text(row, "role") ?: return null
        val day = text(row, "day")?.takeIf(::validDay) ?: return null
        val book = if (row.has("bookId")) count(row.opt("bookId"))?.takeIf { it > 0 } ?: return null else null
        val task = if (row.has("taskId")) text(row, "taskId") ?: return null else null
        val input = count(row.opt("input")) ?: return null
        val output = count(row.opt("output")) ?: return null
        if (count(row.opt("time")) == null || count(row.opt("attempts"))?.let { it > 0 } != true) return null
        val estimated = row.opt("estimated") as? Boolean ?: return null
        AiCostRecord.fromUsage(AiTokenUsage(input, output, estimated), AiCostAttribution(
            book, task, id, provider, AiProviderRole.entries.firstOrNull { it.name == role }, workload, model, day), 1)
    }.getOrNull()

    fun canonicalRow(row: JSONObject): JSONObject {
        require(record(row) != null) { "Invalid usage record" }
        return JSONObject().also { copy -> fields.filter(row::has).forEach { copy.put(it, row.get(it)) } }
    }

    fun digest(requestId: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(requestId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    fun validDay(day: String): Boolean {
        if (!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(day)) return false
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
            isLenient = false; timeZone = TimeZone.getTimeZone("UTC")
        }
        val position = ParsePosition(0)
        val date = format.parse(day, position)
        return date != null && position.index == day.length && format.format(date) == day
    }
}
