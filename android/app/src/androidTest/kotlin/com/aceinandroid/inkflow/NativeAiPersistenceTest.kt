package com.aceinandroid.inkflow

import android.content.*
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aceinandroid.inkflow.ai.*
import com.aceinandroid.inkflow.core.*
import com.aceinandroid.inkflow.core.ai.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class NativeAiPersistenceTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = mutableSetOf<String>()
    private fun sandbox(test: (File) -> Unit) {
        val root = File(base.cacheDir, "ai-contract-${UUID.randomUUID()}").apply { mkdirs() }
        try { test(root) } finally { root.deleteRecursively(); prefs.forEach(base::deleteSharedPreferences) }
    }
    private inner class LocalContext(root: File, name: String) : ContextWrapper(base) {
        private val dir = File(root, name).apply { mkdirs() }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(dir, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(dir, "cache").apply { mkdirs() }
        override fun getDatabasePath(name: String): File = File(dir, "databases/$name").apply { parentFile!!.mkdirs() }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase = SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val id = "${dir.parentFile.name}-${dir.name}-$name"; prefs += id
            return base.getSharedPreferences(id, mode)
        }
    }
    private fun book(context: Context, store: BookStore, name: String): StoredBook {
        val file = File(context.cacheDir, "$name.txt").apply { writeText("$name. First chapter. A passage to read.") }
        return store.import(Uri.fromFile(file))
    }
    @Test fun sessionsAreBookScopedPreserveFutureColumnsAndSurviveZipBackup() = sandbox { root ->
        val source = LocalContext(root, "source")
        val store = BookStore(source)
        val book = book(source, store, "Book")
        val other = book(source, store, "Other").id
        val snapshot = ReadingContextSnapshot(book.title, book.author, "Chapter", progressPercent = 30)
        val repo = AiSessionRepository(store)
        val first = repo.create(book.id, "First", snapshot)
        val second = repo.create(book.id, "Second", snapshot)
        val messages = listOf(AiChatMessage("m1", AiRole.USER, "Question", visibleFromProgress = 30), AiChatMessage("m2", AiRole.ASSISTANT, "Answer", visibleFromProgress = 30, citations = listOf(AiCitation("stable:0", book.id, BookMaterialSource("chapter", "Chapter", "", "Exact quote", .2, 0, 11)))))
        store.writableDatabase.execSQL("UPDATE tb_ai_sessions SET analysisResult = 'opaque-future-value' WHERE id = ?", arrayOf(first.id))
        repo.save(book.id, first.id, messages, "reading.argument", snapshot, "provider-id", "model")
        repo.rename(book.id, first.id, "Renamed")
        assertEquals(2, repo.list(book.id).size)
        assertEquals(0, repo.delete(other, first.id))
        assertTrue(repo.loadMessages(other, first.id).isEmpty())
        assertEquals(messages, repo.loadMessages(book.id, first.id))
        assertEquals("Renamed", repo.list(book.id).first { it.id == first.id }.title)
        assertEquals("opaque-future-value", store.readableDatabase.rawQuery("SELECT analysisResult FROM tb_ai_sessions WHERE id = ?", arrayOf(first.id)).use { it.moveToFirst(); it.getString(0) })
        repo.delete(book.id, second.id)
        NativeAiStore(source).saveConfig(AiProviderConfig(apiKey = "backup-must-not-contain-this-key"))
        val zip = File(root, "backup.zip")
        NativeBackupManager(source).export(Uri.fromFile(zip), store)
        ZipFile(zip).use { archive ->
            assertFalse(archive.entries().asSequence().any { it.name.contains("native_ai_workspace") })
            assertFalse(archive.getInputStream(archive.getEntry(NativeSettingsBackup.ENTRY)).bufferedReader().readText().contains("backup-must-not-contain-this-key"))
        }
        val destination = LocalContext(root, "destination")
        val restored = BookStore(destination)
        try {
            NativeBackupManager(destination).restore(Uri.fromFile(zip), restored)
            assertEquals(messages, AiSessionRepository(restored).loadMessages(book.id, first.id))
            assertFalse(NativeAiStore(destination).loadConfig().isConfigured)
        } finally { restored.close(); store.close() }
    }
    @Test fun sessionAppendPreservesUnknownMessageKindsAndFields() = sandbox { root ->
        val context = LocalContext(root, "future-session")
        val store = BookStore(context)
        try {
            val book = book(context, store, "Future")
            val repo = AiSessionRepository(store)
            val snapshot = ReadingContextSnapshot(book.title, book.author, "Chapter", progressPercent = 30)
            val session = repo.create(book.id, "Future", snapshot)
            val raw = """[{"id":"known","role":"assistant","content":"Answer","visibleFromProgress":30,"futureMetadata":{"kind":"retained"}}, {"id":"opaque","role":"future-tool","payload":{"private":42}}]"""
            store.writableDatabase.execSQL("UPDATE tb_ai_sessions SET messages = ?, contextSnapshot = ? WHERE id = ?",
                arrayOf(raw, """{"futureSource":{"value":"preserved"},"progressPercent":10}""", session.id))
            repo.save(book.id, session.id, repo.loadMessages(book.id, session.id) + AiChatMessage("new", AiRole.USER, "Continue", visibleFromProgress = 30),
                "reading.general", snapshot, "provider", "model")
            val saved = store.readableDatabase.rawQuery("SELECT messages FROM tb_ai_sessions WHERE id = ?", arrayOf(session.id)).use { it.moveToFirst(); org.json.JSONArray(it.getString(0)) }
            assertEquals("retained", saved.getJSONObject(0).getJSONObject("futureMetadata").getString("kind"))
            assertEquals("future-tool", saved.getJSONObject(2).getString("role"))
            assertEquals(42, saved.getJSONObject(2).getJSONObject("payload").getInt("private"))
            val savedSnapshot = store.readableDatabase.rawQuery("SELECT contextSnapshot FROM tb_ai_sessions WHERE id = ?", arrayOf(session.id)).use {
                it.moveToFirst(); org.json.JSONObject(it.getString(0))
            }
            assertEquals("preserved", savedSnapshot.getJSONObject("futureSource").getString("value"))
            assertEquals(30, savedSnapshot.getInt("progressPercent"))
        } finally { store.close() }
    }
    @Test fun providerRolesKeysAndCustomSkillsPersistWithoutPlaintextCredentials() = sandbox { root ->
        val context = LocalContext(root, "config")
        val store = NativeAiStore(context)
        val main = RegisteredAiProvider("main", "Main", AiProviderConfig(apiKey = "secret-main"), listOf("secret-main", "secret-rotation"))
        val light = RegisteredAiProvider("light", "Light", AiProviderConfig(apiKey = "secret-light"))
        store.saveRegistry(AiProviderRegistry(listOf(main, light), "main", "light"))
        val restored = NativeAiStore(context).loadRegistry()
        assertEquals(listOf("secret-main", "secret-rotation"), restored.candidates(AiProviderRole.CONVERSATION).map { it.second.apiKey })
        assertEquals("secret-light", restored.candidates(AiProviderRole.LIGHTWEIGHT).single().second.apiKey)
        assertFalse(context.getSharedPreferences("native_ai_workspace", 0).all.toString().contains("secret-main"))
        assertFalse(NativeSettingsBackup.capture(context).toString().contains("secret-light"))
        store.importSkillJson("""{"id":"custom.review","title":"Review","summary":"Summary","instructions":"Ask one question"}""")
        assertNotNull(NativeAiStore(context).skills().find("custom.review"))
        assertTrue(store.exportSkillJson("custom.review").contains("Ask one question"))
    }
}
