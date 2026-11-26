package com.aceinandroid.inkflow

import android.content.ContentValues
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NativeProgressSyncTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun mergeRejectsStaleAndUnknownPositionsWithoutMovingLocalReader() = withBook { store, bookId, key ->
        val db = store.writableDatabase
        val ownId = NativeProgressIdentity.getOrCreate(context)
        store.savePosition(bookId, 0, "epubcfi(/6/2!/4/2:0)", 0.2)
        val remote = position(key, "other-device", "epubcfi(/6/4!/4/2:0)", 0.8, 200)
        remote.put("chapterTitle", JSONObject.NULL)
        assertEquals(1, NativeProgressSync(context).mergeChanges(db, JSONArray().put(remote), ownId))
        assertEquals(0, NativeProgressSync(context).mergeChanges(db, JSONArray()
            .put(position(key, "other-device", "older", 0.1, 100))
            .put(position("unknown-book", "third", "unknown", 0.9, 300)), ownId))
        db.rawQuery("SELECT cfi, chapter_title FROM tb_book_device_positions WHERE book_id = ? AND device_id = ?",
            arrayOf(bookId.toString(), "other-device")).use {
            assertTrue(it.moveToFirst())
            assertEquals("epubcfi(/6/4!/4/2:0)", it.getString(0))
            assertTrue(it.isNull(1))
        }
        assertEquals("epubcfi(/6/2!/4/2:0)", bookPosition(store, bookId))
        val ownRemote = position(key, ownId, "epubcfi(/6/6!/4/2:0)", 0.95, Long.MAX_VALUE / 2)
        assertEquals(0, NativeProgressSync(context).mergeChanges(db, JSONArray().put(ownRemote), ownId))
        assertEquals("epubcfi(/6/2!/4/2:0)", bookPosition(store, bookId))
    }

    @Test fun cursorAdvancesOnlyAfterUploadAndUnauthorizedClearsSession() = withBook { store, bookId, key ->
        val prefs = context.getSharedPreferences("native_progress_sync", Context.MODE_PRIVATE)
        val sync = FakeSync(context)
        sync.signIn("https://sync.example", "reader", "password")
        store.savePosition(bookId, 0, "epubcfi(/6/2!/4/2:0)", 0.2)
        sync.changes = JSONObject().put("nextCursor", "8").put("changes", JSONArray().put(
            position(key, "other-device", "epubcfi(/6/4!/4/2:0)", 0.8, 200)))
        sync.failUpload = true
        assertThrows(IOException::class.java) { sync.synchronize(store) }
        assertNull(prefs.getString("cursor", null))
        assertEquals("epubcfi(/6/2!/4/2:0)", bookPosition(store, bookId))
        sync.failUpload = false
        assertEquals(0, sync.synchronize(store))
        assertEquals("8", prefs.getString("cursor", null))
        assertTrue(sync.uploads >= 2)
        assertTrue(sync.uploaded.any {
            it.optString("bookKey") == key &&
                it.optJSONObject("locator")?.optString("value") == "epubcfi(/6/2!/4/2:0)"
        })
        sync.unauthorized = true
        assertThrows(ProgressHttpException::class.java) { sync.synchronize(store) }
        assertFalse(sync.account().signedIn)
        assertNull(prefs.getString("cursor", null))
    }

    @Test fun invalidChangeFeedDoesNotAdvanceCursor() = withBook { store, bookId, _ ->
        val prefs = context.getSharedPreferences("native_progress_sync", Context.MODE_PRIVATE)
        val sync = FakeSync(context)
        sync.signIn("https://sync.example", "reader", "password")
        store.savePosition(bookId, 0, "epubcfi(/6/2!/4/2:0)", 0.2)
        sync.changes = JSONObject().put("changes", JSONArray())
        assertThrows(IOException::class.java) { sync.synchronize(store) }
        assertNull(prefs.getString("cursor", null))
    }

    @Test fun automaticSyncRequiresOptInAndRespectsCooldown() = withBook { store, bookId, _ ->
        val sync = FakeSync(context)
        sync.signIn("https://sync.example", "reader", "password")
        store.savePosition(bookId, 0, "epubcfi(/6/2!/4/2:0)", 0.2)
        assertNull(sync.synchronizeAutomatically(store))
        sync.setAutomaticEnabled(true)
        assertEquals(0, sync.synchronizeAutomatically(store))
        val uploaded = sync.uploads
        assertFalse(sync.hasPendingPositions(store))
        assertNull(sync.synchronizeAutomatically(store))
        assertEquals(uploaded, sync.uploads)
        store.savePosition(bookId, 0, "epubcfi(/6/2!/4/4:0)", 0.3)
        assertTrue(sync.hasPendingPositions(store))
        assertEquals(0, sync.synchronizeAutomatically(store))
        assertTrue(sync.uploads > uploaded)
        assertFalse(sync.hasPendingPositions(store))
        assertTrue(sync.uploaded.any {
            it.optJSONObject("locator")?.optString("value") == "epubcfi(/6/2!/4/4:0)"
        })
        sync.signOut()
        assertFalse(sync.automaticEnabled())
    }

    @Test fun failedAutomaticUploadRemainsPendingForRetry() = withBook { store, bookId, _ ->
        val sync = FakeSync(context)
        sync.signIn("https://sync.example", "reader", "password")
        sync.setAutomaticEnabled(true)
        store.savePosition(bookId, 0, "epubcfi(/6/2!/4/2:0)", 0.2)
        sync.failUpload = true
        assertThrows(IOException::class.java) { sync.synchronizeAutomatically(store) }
        assertTrue(sync.hasPendingPositions(store))
        sync.failUpload = false
        assertEquals(0, sync.synchronizeAutomatically(store))
        assertFalse(sync.hasPendingPositions(store))
    }

    @Test fun restoredLibraryInvalidatesUploadedPositionMarker() = withBook { store, bookId, _ ->
        val sync = FakeSync(context)
        sync.signIn("https://sync.example", "reader", "password")
        store.savePosition(bookId, 0, "epubcfi(/6/2!/4/2:0)", 0.2)
        sync.synchronize(store)
        assertFalse(sync.hasPendingPositions(store))
        sync.onLibraryRestored()
        assertTrue(sync.hasPendingPositions(store))
    }

    private fun withBook(test: (BookStore, Long, String) -> Unit) {
        val store = BookStore(context)
        val key = UUID.randomUUID().toString().replace("-", "")
        val bookId = store.writableDatabase.insertOrThrow("tb_books", null, ContentValues().apply {
            put("title", "Sync fixture")
            put("file_path", "file/$key.epub")
            put("file_md5", key)
            put("is_deleted", 0)
        })
        try { test(store, bookId, key) } finally {
            store.writableDatabase.delete("tb_book_device_positions", "book_id = ?", arrayOf(bookId.toString()))
            store.writableDatabase.delete("tb_books", "id = ?", arrayOf(bookId.toString()))
            context.getSharedPreferences("native_progress_sync", Context.MODE_PRIVATE).edit()
                .remove("endpoint").remove("token").remove("expires_at").remove("cursor")
                .remove("automatic").remove("last_auto_attempt").remove("last_uploaded_at").commit()
            store.close()
        }
    }

    private fun bookPosition(store: BookStore, bookId: Long): String = store.readableDatabase.rawQuery(
        "SELECT last_read_position FROM tb_books WHERE id = ?", arrayOf(bookId.toString())).use {
        assertTrue(it.moveToFirst())
        it.getString(0)
    }

    private fun position(key: String, device: String, cfi: String, progress: Double, updated: Long) =
        JSONObject().put("bookKey", key).put("deviceId", device)
            .put("locator", JSONObject().put("type", "cfi").put("value", cfi))
            .put("progress", progress).put("updatedAt", updated)

    private class FakeSync(context: Context) : NativeProgressSync(context) {
        var changes = JSONObject().put("nextCursor", "0").put("changes", JSONArray())
        var failUpload = false
        var unauthorized = false
        var uploads = 0
        val uploaded = mutableListOf<JSONObject>()

        override fun request(endpoint: String, path: String, method: String,
            token: String?, body: JSONObject?): JSONObject = when {
            path == "/v1/auth/login" -> JSONObject().put("accessToken", "test-token")
                .put("expiresAt", "2099-01-01T00:00:00.000Z")
            path == "/health" -> JSONObject().put("protocolVersion", 1)
            unauthorized -> throw ProgressHttpException(401, "unauthorized")
            path.startsWith("/v1/changes") -> changes
            method == "PUT" -> {
                uploads++
                if (body != null) uploaded += body
                if (failUpload) throw IOException("Upload failed")
                JSONObject()
            }
            else -> JSONObject()
        }
    }
}
