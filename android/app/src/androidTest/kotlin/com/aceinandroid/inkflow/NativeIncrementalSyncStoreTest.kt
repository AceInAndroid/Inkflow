package com.aceinandroid.inkflow

import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aceinandroid.inkflow.core.sync.DeltaRow
import com.aceinandroid.inkflow.core.sync.ReadingAgentDelta
import com.aceinandroid.inkflow.core.sync.VocabularyDelta
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeIncrementalSyncStoreTest {
    private val hash = "0123456789abcdef0123456789abcdef"
    private fun database(test: (SQLiteDatabase, NativeIncrementalSyncStore) -> Unit) {
        val db = SQLiteDatabase.create(null)
        try {
            val schema = JSONArray(InstrumentationRegistry.getInstrumentation().targetContext.assets
                .open("schema_v22.json").bufferedReader().use { it.readText() })
            for (i in 0 until schema.length()) db.execSQL(schema.getString(i))
            db.execSQL("INSERT INTO tb_books(id,title,file_md5,is_deleted,last_read_position,reading_percentage) VALUES (42,'Book',?,0,'local',0.2)", arrayOf(hash))
            test(db, NativeIncrementalSyncStore(db, "local"))
        } finally { db.close() }
    }
    private fun pack(table: String, vararg rows: DeltaRow) = NativeIncrementalSyncPackage(
        ReadingAgentDelta.TYPE, "other", 1_000, mapOf(table to rows.toList()), hash)
    private fun memory(id: String, time: Long = 100, text: String = "remote") = mapOf(
        "id" to id, "book_id" to 999L, "title" to "Memory", "markdown" to text,
        "created_at" to 10L, "updated_at" to time)
    private fun scalar(db: SQLiteDatabase, sql: String): String = db.rawQuery(sql, null).use {
        assertTrue(it.moveToFirst()); it.getString(0)
    }

    @Test fun remapsBooksAndLeavesCurrentPositionUntouched() = database { db, store ->
        store.merge(pack("tb_reading_memory_documents", memory("m")), 42)
        store.merge(pack("tb_book_device_positions", mapOf("book_id" to 999L, "device_id" to "other",
            "cfi" to "remote", "progress" to 0.8, "updated_at" to 200L)), 42)
        assertEquals("42", scalar(db, "SELECT book_id FROM tb_reading_memory_documents"))
        assertEquals("local", scalar(db, "SELECT last_read_position FROM tb_books WHERE id=42"))
        assertEquals("0.2", scalar(db, "SELECT reading_percentage FROM tb_books WHERE id=42"))
        assertEquals(hash, store.capture(hash, 42).bookKey)
    }

    @Test fun badRowRollsBackWholePackage() = database { db, store ->
        try {
            store.merge(pack("tb_reading_memory_documents", memory("first"), memory("bad") - "title"), 42)
            fail("Missing required title must reject package")
        } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals("0", scalar(db, "SELECT COUNT(*) FROM tb_reading_memory_documents"))
    }

    @Test fun unknownColumnsRejectBeforeWrites() = database { db, store ->
        try {
            store.merge(pack("tb_reading_memory_documents", memory("first"), memory("future") + ("future_column" to "opaque")), 42)
            fail("Unsupported fields must never be discarded")
        } catch (_: IllegalArgumentException) { }
        assertEquals("0", scalar(db, "SELECT COUNT(*) FROM tb_reading_memory_documents"))
        for (field in listOf("password", "api_key", "native_webdav_account")) {
            try {
                store.merge(pack("tb_reading_memory_documents", memory("private") + (field to "secret-fixture")), 42)
                fail("Sensitive preference field accepted")
            } catch (_: IllegalArgumentException) { }
        }
        val forbidden = setOf("native_webdav_account", "native_ai_workspace", "native_incremental_sync")
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                check(name !in forbidden) { "Portable backup accessed private preferences" }
                return super.getSharedPreferences("incremental_privacy_fixture_$name", mode)
            }
        }
        val exported = store.capture(hash, 42).encode() + store.capture(null).encode() + NativeSettingsBackup.capture(context)
        for (name in forbidden) assertFalse(exported.contains(name))
        assertFalse(exported.contains("secret-fixture"))
    }

    @Test fun tombstonesAreMonotonicAndStopOldRowsResurrecting() = database { db, store ->
        store.merge(pack("tb_reading_memory_documents", memory("m")), 42)
        fun marker(time: Long) = pack("tb_reading_sync_tombstones", mapOf(
            "entity_type" to "memory", "entity_id" to "m", "book_id" to 999L,
            "device_id" to "other", "deleted_at" to time))
        store.merge(marker(200), 42)
        store.merge(marker(50), 42)
        store.merge(pack("tb_reading_memory_documents", memory("m", 150)), 42)
        assertEquals("0", scalar(db, "SELECT COUNT(*) FROM tb_reading_memory_documents"))
        assertEquals("200", scalar(db, "SELECT deleted_at FROM tb_reading_sync_tombstones"))
        store.merge(pack("tb_reading_memory_documents", memory("m", 201)), 42)
        assertEquals("1", scalar(db, "SELECT COUNT(*) FROM tb_reading_memory_documents"))
    }

    @Test fun naturalCheckpointKeysAndSingleActiveGoalArePreserved() = database { db, store ->
        fun checkpoint(id: String, time: Long) = mapOf("id" to id, "book_id" to 999L,
            "chapter_href" to "chapter", "created_at" to 10L, "updated_at" to time)
        store.merge(pack("tb_reading_checkpoints", checkpoint("first", 100)), 42)
        store.merge(pack("tb_reading_checkpoints", checkpoint("second", 200)), 42)
        assertEquals("first", scalar(db, "SELECT id FROM tb_reading_checkpoints"))
        fun goal(id: String, time: Long) = mapOf("id" to id, "book_id" to 999L,
            "title" to id, "status" to "active", "created_at" to 10L, "updated_at" to time)
        store.merge(pack("tb_reading_goals", goal("new", 300), goal("old", 200)), 42)
        assertEquals("new", scalar(db, "SELECT id FROM tb_reading_goals WHERE status='active'"))
        assertEquals("2", scalar(db, "SELECT COUNT(*) FROM tb_reading_goals"))
    }

    @Test fun vocabularyRemapsSourceAndDeletesThroughExistingTombstoneTable() = database { db, store ->
        fun word(id: String, timestamp: String, definition: String): DeltaRow = mapOf("id" to id,
            "word" to "example", "normalized_word" to "example", "book_id" to "999", "book_key" to hash,
            "source_sentence" to "An example", "definition_cn" to definition,
            "next_review_at" to timestamp, "created_at" to "2026-01-01T00:00:00.000Z", "updated_at" to timestamp)
        fun vocabulary(row: DeltaRow) = NativeIncrementalSyncPackage(VocabularyDelta.TYPE, "other", 1_000,
            mapOf("tb_vocabulary" to listOf(row)))
        store.merge(vocabulary(word("remote", "2026-01-02T00:00:00.000Z", "new")))
        assertEquals("42", scalar(db, "SELECT book_id FROM tb_vocabulary"))
        store.merge(vocabulary(word("different-id", "2026-01-03T00:00:00.000Z", "newer")))
        assertEquals("remote", scalar(db, "SELECT id FROM tb_vocabulary"))
        assertEquals("newer", scalar(db, "SELECT definition_cn FROM tb_vocabulary"))
        val precise = "2026-01-03T00:00:00.000002Z"
        store.merge(vocabulary(word("different-id", precise, "microseconds win")))
        store.merge(vocabulary(word("different-id", "2026-01-03T00:00:00.000001Z", "microseconds stale")))
        assertEquals("microseconds win", scalar(db, "SELECT definition_cn FROM tb_vocabulary"))
        assertEquals(1, store.merge(vocabulary(word("different-id", precise, "equal conflict"))))
        assertEquals("microseconds win", scalar(db, "SELECT definition_cn FROM tb_vocabulary"))
        val ceiling = NativeIncrementalSyncStore.vocabularyTime(precise) + 1
        store.deleteVocabulary("example", 1_000) // Clock rollback still covers the entire row timestamp.
        assertEquals(ceiling.toString(), scalar(db, "SELECT deleted_at FROM tb_reading_sync_tombstones WHERE book_id=0"))
        store.merge(vocabulary(word("different-id", precise, "deleted microseconds")))
        assertEquals("0", scalar(db, "SELECT COUNT(*) FROM tb_vocabulary"))
        // A stale local connection may reintroduce a row; deleting it must not decrease our marker.
        db.execSQL("INSERT INTO tb_vocabulary(id,word,normalized_word,book_id,source_sentence,definition_cn,next_review_at,created_at,updated_at) VALUES ('local','example','example','','','','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')")
        store.deleteVocabulary("example", 2_000)
        assertEquals(ceiling.toString(), scalar(db, "SELECT deleted_at FROM tb_reading_sync_tombstones WHERE book_id=0"))
        store.merge(vocabulary(word("remote", "2026-01-03T00:00:00.000Z", "stale")))
        assertEquals("0", scalar(db, "SELECT COUNT(*) FROM tb_vocabulary"))
        assertEquals("und:example", scalar(db, "SELECT entity_id FROM tb_reading_sync_tombstones WHERE book_id=0"))
        assertEquals("und", org.json.JSONObject(store.capture(null).encode()).getString("language"))
    }

    @Test fun missingSpoilerBoundaryCannotIntroduceVisibleWikiContent() = database { db, store ->
        try {
            store.merge(pack("tb_book_wiki_entries", mapOf("id" to "future", "book_id" to 999L,
                "kind" to "future.kind", "title" to "Future", "updated_at" to 100L)), 42)
            fail("Missing visibility must not default to zero")
        } catch (_: IllegalArgumentException) { }
        assertEquals("0", scalar(db, "SELECT COUNT(*) FROM tb_book_wiki_entries"))
        val future = mapOf("id" to "future", "book_id" to 999L, "kind" to "future.kind", "title" to "Future",
            "content_markdown" to "opaque", "visible_from_progress" to 0.8, "user_corrected" to 1L,
            "created_at" to 10L, "updated_at" to 100L)
        store.merge(pack("tb_book_wiki_entries", future), 42)
        assertEquals(1, store.merge(pack("tb_book_wiki_entries", future + mapOf("kind" to "known.kind", "updated_at" to 999L)), 42))
        store.merge(pack("tb_book_wiki_entries", future + mapOf("user_corrected" to 0L, "content_markdown" to "generated",
            "visible_from_progress" to 0.1, "updated_at" to 999L)), 42)
        assertEquals("opaque", scalar(db, "SELECT content_markdown FROM tb_book_wiki_entries"))
        assertEquals("0.8", scalar(db, "SELECT visible_from_progress FROM tb_book_wiki_entries"))
        val captured = store.capture(hash, 42).rows["tb_book_wiki_entries"]!!.single()
        assertEquals("future.kind", captured["kind"])
        assertEquals("opaque", captured["content_markdown"])
    }
}
