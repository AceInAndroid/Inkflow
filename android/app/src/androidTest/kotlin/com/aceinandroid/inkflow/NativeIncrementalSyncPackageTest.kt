package com.aceinandroid.inkflow

import com.aceinandroid.inkflow.core.sync.ReadingAgentDelta
import com.aceinandroid.inkflow.core.sync.VocabularyDelta
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Also runnable in isolation on the JVM with the already cached org.json implementation. */
class NativeIncrementalSyncPackageTest {
    private fun packageData() = NativeIncrementalSyncPackage(ReadingAgentDelta.TYPE, "device-1", 100,
        mapOf("tb_book_wiki_entries" to listOf(mapOf("id" to "entry", "book_id" to 999L,
            "kind" to "future.opaque", "content_markdown" to "{\"kept\":true}", "parent_id" to null))),
        "0123456789abcdef0123456789abcdef")
    private fun reject(text: String) {
        try { NativeIncrementalSyncPackage.decode(text); fail("Invalid package accepted") } catch (_: IOException) { }
    }

    @Test fun flutterEnvelopeUnknownKindsAndNullsRoundTrip() {
        val decoded = NativeIncrementalSyncPackage.decode(packageData().encode())
        assertEquals(ReadingAgentDelta.TYPE, decoded.type)
        assertEquals("device-1", decoded.deviceId)
        val row = decoded.rows["tb_book_wiki_entries"]!!.single()
        assertEquals("future.opaque", row["kind"])
        assertEquals("{\"kept\":true}", row["content_markdown"])
        assertTrue(row.containsKey("parent_id")); assertNull(row["parent_id"])
    }

    @Test fun rejectsMalformedAndUnsupportedVersionTableAndPaths() {
        val good = packageData().encode()
        reject("{")
        reject("[]")
        reject(good + " trailing")
        reject(good.replace("\"schemaVersion\":1", "\"schemaVersion\":99"))
        reject(good.replace("tb_book_wiki_entries", "tb_provider_secrets"))
        reject(good.replace("device-1", "../device-1"))
        reject(good.replace("\"generatedAt\":100", "\"generatedAt\":1.5"))
        reject(good.replace("0123456789abcdef0123456789abcdef", "id-999"))
    }

    @Test fun rejectsOversizedAndDeeplyNestedPackages() {
        reject(" ".repeat(ReadingAgentDelta.MAX_BYTES + 1))
        reject("[".repeat(13) + "0" + "]".repeat(13))
        val rows = List(ReadingAgentDelta.MAX_ROWS + 1) { mapOf("id" to "row-$it") }
        reject(NativeIncrementalSyncPackage(ReadingAgentDelta.TYPE, "device", 100,
            mapOf("tb_reading_memory_documents" to rows), packageData().bookKey).encode())
    }

    @Test fun nestedRowValuesCannotBecomeDatabaseWrites() {
        val root = org.json.JSONObject(packageData().encode())
        root.getJSONObject("rows").getJSONArray("tb_book_wiki_entries").getJSONObject(0)
            .put("content_markdown", org.json.JSONObject().put("nested", true))
        reject(root.toString())
    }

    @Test fun vocabularySchemaIsIndependentAndLanguageIsExplicit() {
        val packageData = NativeIncrementalSyncPackage(VocabularyDelta.TYPE, "device", 100,
            mapOf("tb_vocabulary" to emptyList()))
        val text = packageData.encode()
        assertEquals(VocabularyDelta.TYPE, NativeIncrementalSyncPackage.decode(text).type)
        assertNull(NativeIncrementalSyncPackage.decode(text).bookKey)
        reject(text.replace("\"und\"", "\"en\""))
        reject(text.replace("tb_vocabulary", "tb_book_device_positions"))
    }

    @Test fun rejectsUnknownEnvelopeFieldsAndSensitivePreferenceTables() {
        for (field in listOf("futureExtension", "native_webdav_account", "native_ai_workspace", "native_incremental_sync")) {
            val root = org.json.JSONObject(packageData().encode()).put(field, "private-fixture-secret")
            reject(root.toString())
        }
        for (table in listOf("tb_settings", "native_webdav_account", "native_ai_workspace"))
            reject(packageData().encode().replace("tb_book_wiki_entries", table))
        for (clock in listOf("0", "-1", "253402300800000", "9223372036854775808", "1e100"))
            reject(packageData().encode().replace("\"generatedAt\":100", "\"generatedAt\":$clock"))
    }

}
