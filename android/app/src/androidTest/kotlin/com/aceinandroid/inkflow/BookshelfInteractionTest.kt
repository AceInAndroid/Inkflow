package com.aceinandroid.inkflow

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aceinandroid.inkflow.ui.bookshelf.BookDetailScreen
import com.aceinandroid.inkflow.ui.bookshelf.BookshelfScreen
import com.aceinandroid.inkflow.ui.theme.InkflowTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Isolated book and preference fixtures for the bookshelf UI and store callbacks. */
@RunWith(AndroidJUnit4::class)
class BookshelfInteractionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val base get() = instrumentation.targetContext
    private val preferenceFiles = mutableSetOf<String>()

    private fun isolatedContext(parent: Context, root: File): Context = object : ContextWrapper(parent) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun getDatabasePath(name: String): File = File(root, "databases/$name").apply { parentFile!!.mkdirs() }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
            errorHandler: DatabaseErrorHandler?): SQLiteDatabase = SQLiteDatabase.openDatabase(
            getDatabasePath(name).path, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = "${root.name}-$name"
            preferenceFiles += isolatedName
            return base.getSharedPreferences(isolatedName, mode)
        }
    }

    private fun sandbox(test: (File, BookStore) -> Unit) {
        val root = File(base.cacheDir, "bookshelf-ui-${UUID.randomUUID()}").apply { mkdirs() }
        val context = isolatedContext(base, root)
        val store = BookStore(context)
        val previousFlags = instrumentation.uiAutomation.serviceInfo.flags
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        try { test(root, store) } finally {
            store.close()
            root.deleteRecursively()
            preferenceFiles.forEach(base::deleteSharedPreferences)
            instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply { flags = previousFlags }
        }
    }

    private fun importBook(root: File, store: BookStore, name: String): StoredBook {
        val source = File(root, "$name.txt").apply { writeText("Chapter One\n$name\nA passage for local bookshelf testing.") }
        return store.import(Uri.fromFile(source))
    }

    @Test fun longPressSelectsAndBatchDeletionRequiresConfirmation() = sandbox { root, store ->
        val first = importBook(root, store, "First book")
        val second = importBook(root, store, "Second book")
        assertNotEquals("Batch selection needs two distinct imported books", first.id, second.id)
        assertEquals("Both fixture books must exist before opening the shelf", 2, store.all().size)
        val opened = AtomicInteger()
        var localized: Context = base
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                localized = isolatedContext(activity, root)
                activity.setContent {
                    CompositionLocalProvider(LocalContext provides localized) {
                        InkflowTheme {
                            var books by remember { mutableStateOf(store.all()) }
                            BookshelfScreen(books, store, false, remember { SnackbarHostState() },
                                onLibraryChanged = { books = store.all() }, onImport = {},
                                onOpen = { opened.incrementAndGet() },
                                onRemove = { error("Batch deletion must use its own confirmation") })
                        }
                    }
                }
            }
            perform(first.title, AccessibilityNodeInfo.ACTION_LONG_CLICK)
            waitFor(localized.getString(R.string.bookshelf_selected_count, 1))
            assertEquals(0, opened.get())
            perform(localized.getString(R.string.bookshelf_select_all))
            waitFor(localized.getString(R.string.bookshelf_selected_count, 2))
            perform(localized.getString(R.string.bookshelf_delete_selected))
            waitFor(localized.getString(R.string.bookshelf_delete_title, 2))
            assertEquals(2, store.all().size)
            assertTrue(first.file.isFile)
            assertTrue(second.file.isFile)
            perform(localized.getString(R.string.cancel))
            waitUntil { find(localized.getString(R.string.bookshelf_delete_title, 2)) == null }
            assertEquals(2, store.all().size)
            perform(localized.getString(R.string.bookshelf_delete_selected))
            waitFor(localized.getString(R.string.bookshelf_delete_title, 2))
            perform(localized.getString(R.string.remove_book))
            waitUntil { store.all().isEmpty() }
            assertFalse(first.file.exists())
            assertFalse(second.file.exists())
            assertEquals(0, opened.get())
        }
    }

    @Test fun fullScreenDetailsExposeReadAndListenCallbacks() = sandbox { root, store ->
        val book = importBook(root, store, "Detail fixture")
        val reads = AtomicInteger()
        val listens = AtomicInteger()
        var localized: Context = base
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                localized = isolatedContext(activity, root)
                activity.setContent {
                    CompositionLocalProvider(LocalContext provides localized) {
                        InkflowTheme {
                            BookDetailScreen(book, store, onDismiss = {}, onSaved = {},
                                onRead = { reads.incrementAndGet() }, onListen = { listens.incrementAndGet() })
                        }
                    }
                }
            }
            waitFor(book.title)
            perform(localized.getString(R.string.bookshelf_listen))
            waitUntil { listens.get() == 1 }
            assertEquals(0, reads.get())
            perform(localized.getString(R.string.migration_read_book))
            waitUntil { reads.get() == 1 }
            assertEquals(1, listens.get())
        }
    }

    private fun find(label: String): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (!node.refresh()) return null
            if (node.isVisibleToUser && (node.text?.toString() == label || node.contentDescription?.toString() == label))
                return node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { child -> search(child)?.let { return it } }
            }
            return null
        }
        if (android.os.Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
        return instrumentation.uiAutomation.windows.asSequence().mapNotNull { it.root }
            .mapNotNull(::search).firstOrNull()
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        val labels = mutableListOf<String>()
        fun collect(node: AccessibilityNodeInfo?) {
            if (node == null) return
            (node.text ?: node.contentDescription)?.toString()?.let(labels::add)
            for (i in 0 until node.childCount) collect(node.getChild(i))
        }
        collect(instrumentation.uiAutomation.rootInActiveWindow)
        runCatching { instrumentation.uiAutomation.takeScreenshot()?.let { image ->
            java.io.File(base.getExternalFilesDir(null), "bookshelf-failure.png").outputStream().use {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        } }
        assertTrue("Timed out waiting for bookshelf state: ${labels.take(40)}", condition())
    }

    private fun waitFor(label: String) { waitUntil { find(label) != null } }

    private fun perform(label: String, action: Int = AccessibilityNodeInfo.ACTION_CLICK) {
        instrumentation.waitForIdleSync()
        waitUntil {
            var node = find(label)
            while (node != null) {
                if (node.actionList.any { it.id == action }) {
                    val accepted = node.performAction(action)
                    if (accepted) {
                        instrumentation.waitForIdleSync()
                        runCatching { instrumentation.uiAutomation.waitForIdle(250, 3_000) }
                    }
                    return@waitUntil accepted
                }
                node = node.parent
            }
            false
        }
    }
}
