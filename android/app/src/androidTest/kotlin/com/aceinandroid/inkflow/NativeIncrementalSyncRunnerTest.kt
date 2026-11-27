package com.aceinandroid.inkflow

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

/** Exercises the real runner and SQLite boundary against a conditional-write WebDAV fixture. */
@RunWith(AndroidJUnit4::class)
class NativeIncrementalSyncRunnerTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = mutableSetOf<String>()
    private inner class LocalContext(private val dir: File) : ContextWrapper(base) {
        init { dir.mkdirs() }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(dir, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(dir, "cache").apply { mkdirs() }
        override fun getDatabasePath(name: String): File = File(dir, "db/$name").apply { parentFile!!.mkdirs() }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
            SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val key = "${dir.parentFile!!.name}-${dir.name}-$name"
            preferences += key
            return base.getSharedPreferences(key, mode)
        }
    }
    private class Server : WebDavClient {
        data class Entry(val text: String, val etag: String)
        val entries = linkedMapOf<String, Entry>()
        var revision = 0
        var rejectWrites = false
        override fun ensureDirectory(path: String) = Unit
        override fun resources(path: String, depth: Int): List<WebDavResource> = entries.filterKeys {
            it.substringBeforeLast('/') == path
        }.map { (key, value) -> WebDavResource(key.substringAfterLast('/'), false,
            value.text.toByteArray().size.toLong(), null, value.etag) }
        override fun download(path: String, target: File, maxBytes: Long) {
            val text = entries.getValue(path).text
            require(text.toByteArray().size <= maxBytes)
            target.writeText(text)
        }
        override fun upload(path: String, file: File, condition: WebDavWriteCondition?) {
            val current = entries[path]
            if (rejectWrites || when (condition) {
                WebDavWriteCondition.Absent -> current != null
                is WebDavWriteCondition.Match -> current?.etag != condition.etag
                null -> true
            }) throw IOException("WebDAV PUT failed: 412")
            entries[path] = Entry(file.readText(), "\"${++revision}\"")
        }
    }
    private fun sandbox(test: (File) -> Unit) {
        val root = File(base.cacheDir, "delta-runner-${UUID.randomUUID()}").apply { mkdirs() }
        try { test(root) } finally {
            root.deleteRecursively()
            preferences.forEach(base::deleteSharedPreferences)
        }
    }
    private fun import(context: Context, store: BookStore, text: String): StoredBook =
        store.import(Uri.fromFile(File(context.cacheDir, "Book.txt").apply { writeText(text) }))

    @Test fun twoInstallationsExchangeBookRowsWithoutReplacingReadingPosition() = sandbox { root ->
        val firstContext = LocalContext(File(root, "first"))
        val secondContext = LocalContext(File(root, "second"))
        val first = BookStore(firstContext)
        val second = BookStore(secondContext)
        try {
            val a = import(firstContext, first, "Shared text. One paragraph.")
            import(secondContext, second, "Another book, shifts the database ID.")
            val b = import(secondContext, second, "Shared text. One paragraph.")
            assertNotEquals(a.id, b.id)
            first.writableDatabase.execSQL("INSERT INTO tb_reading_memory_documents(id,book_id,title,markdown,created_at,updated_at) VALUES ('memory',?,'Title','User memory',10,100)", arrayOf(a.id))
            val server = Server()
            assertEquals(3, NativeIncrementalSync(firstContext).synchronize(first, server).uploaded)
            NativeIncrementalSync(secondContext).synchronize(second, server)
            second.readableDatabase.rawQuery("SELECT book_id,markdown FROM tb_reading_memory_documents WHERE id='memory'", null).use {
                assertTrue(it.moveToFirst()); assertEquals(b.id, it.getLong(0)); assertEquals("User memory", it.getString(1))
            }
            assertEquals(0.0, second.all().first { it.id == b.id }.progress, 0.00001)
            // The second installation also publishes its unrelated filler book branch.
            assertEquals(7, server.entries.size)
            assertEquals(2, server.entries.keys.count { it.contains("reading-skills/v1") })
            assertEquals(3, server.entries.keys.count { it.contains("reading-agent") })
            assertEquals(2, server.entries.keys.count { it.contains("vocabulary/v1") })
        } finally { first.close(); second.close() }
    }

    @Test fun futureOwnBranchAndConditionalWriteConflictsCannotBeOverwritten() = sandbox { root ->
        val context = LocalContext(File(root, "first"))
        val store = BookStore(context)
        try {
            import(context, store, "Shared text. One paragraph.")
            val sync = NativeIncrementalSync(context)
            val server = Server()
            sync.synchronize(store, server)
            val key = server.entries.keys.first { it.contains("reading-agent") }
            val old = server.entries.getValue(key)
            val future = JSONObject(old.text).put("schemaVersion", 2).toString()
            server.entries[key] = Server.Entry(future, old.etag)
            val report = sync.synchronize(store, server)
            assertTrue(report.conflicts > 0); assertTrue(report.ignored > 0)
            assertEquals(future, server.entries.getValue(key).text)
            server.rejectWrites = true
            assertTrue(sync.synchronize(store, server).conflicts >= 2)
            assertEquals(future, server.entries.getValue(key).text)
        } finally { store.close() }
    }
}
