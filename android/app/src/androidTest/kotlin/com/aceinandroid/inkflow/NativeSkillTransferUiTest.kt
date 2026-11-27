package com.aceinandroid.inkflow

import android.os.Build
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aceinandroid.inkflow.ai.NativeAiStore
import com.aceinandroid.inkflow.core.ai.ReadingSkill
import com.aceinandroid.inkflow.core.ai.SkillCodec
import com.aceinandroid.inkflow.transfer.SkillTransferInbox
import com.aceinandroid.inkflow.ui.settings.WirelessTransferScreen
import com.aceinandroid.inkflow.ui.theme.InkflowTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Real inbox/store and the real screen. The Activity only hosts content; all test data is isolated.
 * No listener is started: receiving is staged through the same durable inbox used by Wi-Fi uploads. */
@RunWith(AndroidJUnit4::class)
class NativeSkillTransferUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private class IsolatedContext(base: Context, val root: File) : ContextWrapper(base) {
        private val preferences = mutableSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
        override fun getDatabasePath(name: String) = File(root, "databases/$name").apply { parentFile!!.mkdirs() }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
            SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
        override fun deleteDatabase(name: String) = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
        override fun databaseList(): Array<String> = File(root, "databases").list()?.toList()?.toTypedArray() ?: emptyArray()
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val privateName = "${root.name}-$name"
            synchronized(preferences) { preferences += privateName }
            return baseContext.getSharedPreferences(privateName, mode)
        }
        fun cleanup() {
            synchronized(preferences) { preferences.forEach { baseContext.deleteSharedPreferences(it) } }
            root.deleteRecursively()
        }
    }

    private class Fixture(val context: IsolatedContext, val books: BookStore, val book: StoredBook) {
        val ai = NativeAiStore(context)
        val inbox = SkillTransferInbox(context)
        val importedCallbacks = AtomicInteger()
        val keep = ReadingSkill("custom.keep_binding", "Keep current choice", "Existing local choice", "Keep the reader choice.")
        val incoming = ReadingSkill("custom.wireless_review", "Incoming reading method", "Review only the source passage",
            "Incoming method instructions. Do not install before explicit confirmation.", category = "general",
            recommendedAgent = "agent.story", quickPrompts = listOf("Explain the source"), outputFormat = "markdown")
        lateinit var workspaceBefore: Map<String, *>
        lateinit var definitionsBefore: String

        fun seedBindings() {
            ai.applySkillImport(ai.previewSkillImport(SkillCodec.encodeJson(keep)))
            ai.selectAgent("agent.background")
            ai.bindAgentSkill("agent.background", keep.id)
            ai.bindAgentSkill("agent.language", "reading.language")
            ai.setAgentForBook(book.id, "agent.language")
            ai.setSkillForBook(book.id, keep.id, "agent.language")
            workspaceBefore = context.getSharedPreferences("native_ai_workspace", Context.MODE_PRIVATE).all.toMap()
            definitionsBefore = ai.exportStoredSkills()
        }
        fun stage(): SkillTransferInbox.Entry = inbox.receive("incoming.skill.md", SkillCodec.encodeMarkdown(incoming).toByteArray(Charsets.UTF_8))
        fun definitions(): List<ReadingSkill> = SkillCodec.decodeStored(ai.exportStoredSkills())
        fun saveLocal(skill: ReadingSkill, overwrite: Boolean = false) =
            ai.applySkillImport(ai.previewSkillImport(SkillCodec.encodeJson(skill)), overwrite)
        fun assertBindingsUnchanged() {
            assertEquals("agent.background", ai.selectedAgent().id)
            assertEquals(keep.id, ai.skillForAgent("agent.background").id)
            assertEquals("reading.language", ai.skillForAgent("agent.language").id)
            assertEquals("agent.language", ai.agentForBook(book.id).id)
            assertEquals(keep.id, ai.skillForBook(book.id).id)
            assertEquals(workspaceBefore, context.getSharedPreferences("native_ai_workspace", Context.MODE_PRIVATE).all.toMap())
            assertEquals(0, importedCallbacks.get())
            assertEquals(listOf(book.id), books.all().map { it.id })
        }
    }

    private fun fixture(test: (Fixture, (SkillTransferInbox.Entry) -> Unit) -> Unit) {
        val base = instrumentation.targetContext
        val english = base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.ENGLISH) })
        val context = IsolatedContext(english, File(base.cacheDir, "skill-ui-${UUID.randomUUID()}").apply { mkdirs() })
        val books = BookStore(context)
        var activity: Activity? = null
        try {
            val file = File(context.filesDir, "Binding fixture.txt").apply { writeText("Chapter One\n${context.root.name}\nExisting book for local bindings.") }
            val book = books.import(Uri.fromFile(file))
            val databasePath = File(books.writableDatabase.path).canonicalPath
            val isolatedPrefix = context.root.canonicalPath + File.separator
            assertTrue("Database must remain under the isolated fixture root: $databasePath", databasePath.startsWith(isolatedPrefix))
            assertNotEquals(base.filesDir.canonicalPath, context.filesDir.canonicalPath)
            assertNotEquals(base.cacheDir.canonicalPath, context.cacheDir.canonicalPath)
            val f = Fixture(context, books, book)
            f.seedBindings()
            val mount: (SkillTransferInbox.Entry) -> Unit = { entry ->
                check(activity == null) { "Mount the real screen only once per isolated fixture" }
                val host = instrumentation.startActivitySync(Intent(base, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }) as ComponentActivity
                activity = host
                instrumentation.runOnMainSync {
                    host.setContent {
                        CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides context.resources.configuration) {
                            InkflowTheme {
                                WirelessTransferScreen(books, onBack = {}, onImported = { f.importedCallbacks.incrementAndGet() })
                            }
                        }
                    }
                }
                instrumentation.waitForIdleSync()
                // A resumed Activity can precede its first Compose accessibility frame.
                waitFor(context.getString(R.string.p2_wireless_transfer))
                scrollTo(context.getString(R.string.skill_transfer_review, entry.name))
            }
            test(f, mount)
        } finally {
            activity?.let { instrumentation.runOnMainSync { it.finish() } }
            instrumentation.waitForIdleSync()
            books.close()
            context.cleanup()
        }
    }

    @Test fun receivingAndPreviewingDoNotInstallUntilExplicitConfirmationAndBindingsStayUnchanged() = fixture { f, mount ->
        val entry = f.stage()
        assertEquals(listOf(entry.id), f.inbox.pending().map { it.id })
        assertEquals(f.definitionsBefore, f.ai.exportStoredSkills())
        assertNull(f.ai.skills().find(f.incoming.id))
        f.assertBindingsUnchanged()
        mount(entry)
        assertEquals(f.definitionsBefore, f.ai.exportStoredSkills())
        openPreview(f, entry)
        assertEquals(f.definitionsBefore, f.ai.exportStoredSkills())
        assertEquals(1, f.inbox.pending().size)
        f.assertBindingsUnchanged()
        click(f.context.getString(R.string.skill_transfer_back))
        assertEquals(f.definitionsBefore, f.ai.exportStoredSkills())
        openPreview(f, entry)
        click(f.context.getString(R.string.skill_transfer_confirm))
        waitUntil("Explicit confirmation installs the definition and drains the inbox") {
            f.ai.skills().find(f.incoming.id) == f.incoming && f.inbox.pending().isEmpty()
        }
        scrollTo(f.context.getString(R.string.skill_transfer_empty))
        assertEquals(listOf(f.keep, f.incoming), f.definitions())
        assertFalse(File(f.context.filesDir, "skill-transfer-inbox/${entry.id}").exists())
        f.assertBindingsUnchanged()
    }

    @Test fun sameIdDefinitionAddedAfterPreviewRejectsOldApprovalThenFreshReviewImportsACopy() = fixture { f, mount ->
        val entry = f.stage()
        mount(entry)
        openPreview(f, entry)
        val local = f.incoming.copy(title = "New local definition", instructions = "Preserve the new local instructions.")
        f.saveLocal(local)
        val definitionsAfterLocalEdit = f.ai.exportStoredSkills()
        assertNotEquals(entry.version, f.inbox.preview(entry.id).version)
        click(f.context.getString(R.string.skill_transfer_confirm))
        awaitRejectedApproval(f)
        assertEquals(definitionsAfterLocalEdit, f.ai.exportStoredSkills())
        assertEquals(listOf(entry.id), f.inbox.pending().map { it.id })
        assertFalse(File(f.context.filesDir, "skill-transfer-inbox/.receipts/${entry.id}").exists())
        f.assertBindingsUnchanged()
        openPreview(f, entry)
        assertEquals(definitionsAfterLocalEdit, f.ai.exportStoredSkills())
        click(f.context.getString(R.string.skill_transfer_confirm))
        waitUntil("A freshly reviewed conflict imports one copy and drains the inbox") {
            f.inbox.pending().isEmpty() && f.definitions().size == 3
        }
        assertEquals(local, f.ai.skills().find(local.id))
        assertEquals(f.incoming.copy(id = "${f.incoming.id}.copy1"), f.ai.skills().find("${f.incoming.id}.copy1"))
        f.assertBindingsUnchanged()
    }

    /** Existing conflicts must also invalidate approval when their local contents change. */
    @Test fun existingSameIdDefinitionEditedAfterPreviewRequiresNewReviewWithoutImportingOrOverwriting() = fixture { f, mount ->
        val local = f.incoming.copy(title = "Local original", instructions = "Original local instructions.")
        f.saveLocal(local)
        val entry = f.stage()
        assertTrue(f.incoming.id in entry.preview.conflictingIds)
        mount(entry)
        openPreview(f, entry)
        val edited = local.copy(instructions = "Local edit made after the preview opened.")
        f.saveLocal(edited, overwrite = true)
        val definitionsAfterLocalEdit = f.ai.exportStoredSkills()
        click(f.context.getString(R.string.skill_transfer_confirm))
        awaitRejectedApproval(f)
        assertEquals("Stale approval must not add a copy or overwrite the concurrent local definition",
            definitionsAfterLocalEdit, f.ai.exportStoredSkills())
        assertEquals(edited, f.ai.skills().find(edited.id))
        assertNull(f.ai.skills().find("${f.incoming.id}.copy1"))
        assertEquals(listOf(entry.id), f.inbox.pending().map { it.id })
        f.assertBindingsUnchanged()
    }

    private fun openPreview(f: Fixture, entry: SkillTransferInbox.Entry) {
        val review = f.context.getString(R.string.skill_transfer_review, entry.name)
        scrollTo(review)
        click(review)
        waitFor(f.context.getString(R.string.skill_transfer_preview))
        waitFor(f.incoming.title)
        waitFor(f.incoming.id)
        scrollTo(f.incoming.instructions)
        waitFor(f.incoming.instructions)
    }
    private fun awaitRejectedApproval(f: Fixture) {
        val changed = f.context.getString(R.string.inkflow_skill_transfer_changed)
        waitUntil("The old approval must be rejected, or an incorrect import must become observable") {
            find(changed) != null || f.inbox.pending().isEmpty()
        }
        assertFalse("A stale preview must leave the upload pending for a fresh review", f.inbox.pending().isEmpty())
        scrollTo(changed)
        assertNotNull(find(changed))
        assertNull("Rejecting stale approval closes the old preview", find(f.context.getString(R.string.skill_transfer_preview)))
    }
    private fun freshRoot(): AccessibilityNodeInfo? {
        val automation = instrumentation.uiAutomation
        if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
        return automation.rootInActiveWindow
    }
    private fun find(label: String): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null || !node.refresh()) return null
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
            return null
        }
        return walk(freshRoot())
    }
    private fun waitFor(label: String): AccessibilityNodeInfo {
        repeat(80) { find(label)?.let { return it }; SystemClock.sleep(100) }
        throw AssertionError("Missing $label; visible=${labels()}")
    }
    private fun waitUntil(description: String, condition: () -> Boolean) {
        repeat(80) { if (condition()) return; SystemClock.sleep(100) }
        assertTrue("$description; visible=${labels()}", condition())
    }
    private fun click(label: String) {
        var node: AccessibilityNodeInfo? = waitFor(label)
        while (node != null) {
            if (!node.refresh()) break
            if (node.isClickable && node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            node = node.parent
        }
        fail("Cannot click $label")
    }
    private fun scrollTo(label: String) {
        fun scrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null || !node.refresh()) return null
            if (node.isScrollable) return node
            for (i in 0 until node.childCount) scrollable(node.getChild(i))?.let { return it }
            return null
        }
        // An empty startup/window-transition tree must not consume the scroll budget.
        waitUntil("Target $label or its scrollable hierarchy is ready") {
            find(label) != null || scrollable(freshRoot()) != null
        }
        val deadline = SystemClock.uptimeMillis() + 8_000
        var scrolls = 0
        while (SystemClock.uptimeMillis() < deadline) {
            if (find(label)?.isVisibleToUser == true) return
            if (scrolls < 15 && scrollable(freshRoot())
                    ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true) scrolls++
            // Also yield when the root is absent or scrolling is temporarily unavailable.
            SystemClock.sleep(100)
        }
        assertTrue("Cannot expose $label; visible=${labels()}", find(label)?.isVisibleToUser == true)
    }
    private fun labels(): List<String> {
        val values = mutableListOf<String>()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || !node.refresh()) return
            node.text?.toString()?.let(values::add)
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(freshRoot())
        return values
    }
}
