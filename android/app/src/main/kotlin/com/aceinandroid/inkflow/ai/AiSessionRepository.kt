package com.aceinandroid.inkflow.ai

import android.content.ContentValues
import com.aceinandroid.inkflow.BookStore
import com.aceinandroid.inkflow.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class StoredAiSession(val id: String, val title: String, val bookId: Long, val skillId: String, val updatedAt: Long)

/** Existing v22 table, also included by the existing whole-database ZIP backup. */
class AiSessionRepository(private val store: BookStore) {
    fun list(bookId: Long): List<StoredAiSession> = store.readableDatabase.query(
        "tb_ai_sessions", arrayOf("id", "title", "bookId", "readingMode", "updatedAt"),
        "bookId = ?", arrayOf(bookId.toString()), null, null, "updatedAt DESC, id",
    ).use { c -> buildList { while (c.moveToNext()) add(StoredAiSession(c.getString(0), c.getString(1).orEmpty(), c.getLong(2), c.getString(3) ?: "reading.general", c.getLong(4))) } }

    fun create(bookId: Long, title: String, snapshot: ReadingContextSnapshot): StoredAiSession {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        store.writableDatabase.insertOrThrow("tb_ai_sessions", null, ContentValues().apply {
            put("id", id); put("title", title.trim().take(200)); put("bookId", bookId)
            put("bookTitle", snapshot.bookTitle); put("chapterTitle", snapshot.chapterTitle)
            put("readingMode", "reading.general"); put("createdAt", now); put("updatedAt", now)
            put("contextSnapshot", JSONObject().put("progressPercent", snapshot.progressPercent).toString())
        })
        return StoredAiSession(id, title, bookId, "reading.general", now)
    }

    fun loadMessages(bookId: Long, id: String): List<AiChatMessage> = store.readableDatabase.query(
        "tb_ai_sessions", arrayOf("messages", "contextSnapshot"), "id = ? AND bookId = ?", arrayOf(id, bookId.toString()), null, null, null,
    ).use { c ->
        if (!c.moveToFirst()) return@use emptyList()
        // Older/foreign conversations without a provable boundary remain visible only at 100%.
        val boundary = runCatching { JSONObject(c.getString(1)).optInt("progressPercent", 100) }.getOrDefault(100)
        decodeMessages(c.getString(0), boundary)
    }

    fun save(bookId: Long, id: String, messages: List<AiChatMessage>, skillId: String, snapshot: ReadingContextSnapshot,
             service: String, model: String) {
        var previousSnapshot = JSONObject()
        val previous = store.readableDatabase.query("tb_ai_sessions", arrayOf("messages", "contextSnapshot"),
            "id = ? AND bookId = ?", arrayOf(id, bookId.toString()), null, null, null).use {
            if (it.moveToFirst()) {
                previousSnapshot = runCatching { JSONObject(it.getString(1)) }.getOrDefault(JSONObject())
                runCatching { JSONArray(it.getString(0)) }.getOrDefault(JSONArray())
            } else JSONArray()
        }
        val encoded = encodeMessages(messages)
        val byId = (0 until previous.length()).mapNotNull { i -> previous.optJSONObject(i)?.let { it.optString("id", "restored-$i") to it } }.toMap()
        val merged = JSONArray()
        for (i in 0 until encoded.length()) {
            val update = encoded.getJSONObject(i)
            val old = byId[update.getString("id")]?.let { JSONObject(it.toString()) } ?: JSONObject()
            for (key in update.keys()) old.put(key, update.get(key))
            merged.put(old)
        }
        // Preserve opaque future message kinds/roles instead of erasing them on a native append.
        for (i in 0 until previous.length()) {
            val raw = previous.optJSONObject(i)
            if (raw == null || AiRole.entries.none { it.name.equals(raw.optString("role"), true) }) merged.put(previous.get(i))
        }
        check(store.writableDatabase.update("tb_ai_sessions", ContentValues().apply {
            put("messages", merged.toString()); put("readingMode", skillId)
            put("service", service); put("model", model); put("chapterTitle", snapshot.chapterTitle)
            put("contextSnapshot", previousSnapshot.put("progressPercent", snapshot.progressPercent)
                .put("readingProgress", snapshot.readingProgress).put("chapterIndex", snapshot.chapterIndex)
                .put("readOffset", snapshot.readOffset).toString())
            put("updatedAt", System.currentTimeMillis())
        }, "id = ? AND bookId = ?", arrayOf(id, bookId.toString())) == 1) { "Session is no longer available" }
    }

    fun rename(bookId: Long, id: String, title: String) {
        require(title.isNotBlank())
        check(store.writableDatabase.update("tb_ai_sessions", ContentValues().apply {
            put("title", title.trim().take(200)); put("updatedAt", System.currentTimeMillis())
        }, "id = ? AND bookId = ?", arrayOf(id, bookId.toString())) == 1)
    }
    fun delete(bookId: Long, id: String) = store.writableDatabase.delete("tb_ai_sessions", "id = ? AND bookId = ?", arrayOf(id, bookId.toString()))

    companion object {
        fun encodeMessages(messages: List<AiChatMessage>) = JSONArray().apply {
            messages.forEach { m -> put(JSONObject().apply {
                put("id", m.id); put("role", m.role.name.lowercase()); put("content", m.content)
                put("quote", m.quote); put("timestampMs", m.timestampMs); put("isError", m.isError)
                put("visibleFromProgress", m.visibleFromProgress); put("chapterIndex", m.chapterIndex); put("readOffset", m.readOffset); put("sourceChapterTitle", m.sourceChapterTitle); put("sourceProgress", m.sourceProgress)
            }) }
        }
        fun decodeMessages(raw: String, fallbackBoundary: Int = 100): List<AiChatMessage> = runCatching {
            val array = JSONArray(raw)
            buildList { for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val role = AiRole.entries.firstOrNull { it.name.equals(obj.optString("role"), true) } ?: continue
                add(AiChatMessage(obj.optString("id", "restored-$i"), role, obj.optString("content"),
                    obj.optString("quote").takeIf { it.isNotBlank() && it != "null" }, obj.optLong("timestampMs"), obj.optBoolean("isError"),
                    obj.optInt("visibleFromProgress", fallbackBoundary).coerceIn(0, 100), obj.optInt("chapterIndex", -1), obj.optInt("readOffset", -1), obj.optString("sourceChapterTitle"), obj.optDouble("sourceProgress", fallbackBoundary / 100.0)))
            } }
        }.getOrDefault(emptyList())
    }
}
