package com.aceinandroid.inkflow

import com.aceinandroid.inkflow.core.sync.DeltaRow
import com.aceinandroid.inkflow.core.sync.ReadingAgentDelta
import com.aceinandroid.inkflow.core.sync.VocabularyDelta
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException

/** Deliberately strict envelope; unknown row kinds/payload strings are preserved verbatim. */
internal data class NativeIncrementalSyncPackage(
    val type: String,
    val deviceId: String,
    val generatedAt: Long,
    val rows: Map<String, List<DeltaRow>>,
    val bookKey: String? = null,
) {
    fun encode(): String = JSONObject().apply {
        put("type", type); put("schemaVersion", 1); put("deviceId", deviceId)
        put("generatedAt", generatedAt)
        bookKey?.let { put("bookKey", it) }
        if (type == VocabularyDelta.TYPE) put("language", VocabularyDelta.LANGUAGE)
        put("rows", JSONObject().apply {
            rows.forEach { (table, records) -> put(table, JSONArray().apply {
                records.forEach { row -> put(JSONObject().apply {
                    row.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
                }) }
            }) }
        })
    }.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= ReadingAgentDelta.MAX_BYTES) }

    companion object {
        private val segment = Regex("[a-zA-Z0-9._-]{1,128}")
        fun validDevice(value: String): Boolean = segment.matches(value) && value != "." && value != ".."

        fun decode(text: String): NativeIncrementalSyncPackage {
            try {
                require(text.toByteArray(Charsets.UTF_8).size <= ReadingAgentDelta.MAX_BYTES)
                // Reject pathological nesting before handing untrusted text to the platform parser.
                var depth = 0
                var quoted = false
                var escaped = false
                for (char in text) {
                    if (quoted) {
                        if (escaped) escaped = false
                        else if (char == '\\') escaped = true
                        else if (char == '"') quoted = false
                    } else when (char) {
                        '"' -> quoted = true
                        '{', '[' -> { depth++; require(depth <= 12) }
                        '}', ']' -> { depth--; require(depth >= 0) }
                    }
                }
                require(depth == 0 && !quoted)
                val parser = JSONTokener(text)
                val root = parser.nextValue()
                require(root is JSONObject && parser.nextClean() == '\u0000')
                require(root.get("schemaVersion") == 1)
                val type = root.getString("type")
                require(type == ReadingAgentDelta.TYPE || type == VocabularyDelta.TYPE)
                val device = root.getString("deviceId")
                require(validDevice(device))
                val generated = integer(root.get("generatedAt"))
                require(NativeIncrementalSyncTimestamp.validMillis(generated))
                val book = if (type == ReadingAgentDelta.TYPE) root.getString("bookKey").also {
                    require(ReadingAgentDelta.bookKey(it) == it)
                } else null
                if (type == VocabularyDelta.TYPE) require(root.getString("language") == VocabularyDelta.LANGUAGE)
                val expected = setOf("type", "schemaVersion", "deviceId", "generatedAt", "rows",
                    if (type == ReadingAgentDelta.TYPE) "bookKey" else "language")
                require(root.keys().asSequence().toSet() == expected) { "Unsupported envelope fields" }
                val tables = root.getJSONObject("rows")
                val allowed = if (type == ReadingAgentDelta.TYPE) ReadingAgentDelta.tables.map { it.name }.toSet()
                    else setOf("tb_vocabulary", "tb_reading_sync_tombstones")
                var count = 0
                val rows = tables.keys().asSequence().associateWith { table ->
                    require(table in allowed) { "Unsupported delta table" }
                    val records = tables.getJSONArray(table)
                    count += records.length()
                    require(count <= ReadingAgentDelta.MAX_ROWS)
                    (0 until records.length()).map { index ->
                        val record = records.getJSONObject(index)
                        require(record.length() <= 100)
                        record.keys().asSequence().associateWith { key ->
                            require(key.matches(Regex("[a-z_]{1,80}")))
                            val value = record.get(key)
                            require(value === JSONObject.NULL || value is String || value is Number)
                            if (value is Number) require(value.toDouble().isFinite())
                            if (value is String) require(value.length <= 256 * 1024)
                            if (value === JSONObject.NULL) null else value
                        }
                    }
                }
                return NativeIncrementalSyncPackage(type, device, generated, rows, book)
            } catch (failure: Exception) {
                throw IOException("Unsupported or malformed incremental sync package", failure)
            }
        }

        private fun integer(value: Any): Long {
            require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toLong().toDouble())
            return value.toLong()
        }
    }
}
