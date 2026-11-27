package com.aceinandroid.inkflow

import android.content.ContentValues
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.accessibility.AccessibilityNodeInfo
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import com.aceinandroid.inkflow.localization.NativeLocaleContext
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReadingNoteWorkspaceUiTest {
    @Test fun editorAndCreationWorkOnDevice() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val localized = NativeLocaleContext(context)
        val store = BookStore(context)
        val db = store.writableDatabase
        val bookId = db.insertOrThrow("tb_books", null, ContentValues().apply {
            put("title", "Workspace UI fixture")
            put("file_path", "file/${UUID.randomUUID()}.epub")
            put("is_deleted", 0)
        })
        val noteId = UUID.randomUUID().toString()
        db.insertOrThrow("tb_reading_notes", null, ContentValues().apply {
            put("id", noteId)
            put("book_id", bookId)
            put("title", "Workspace UI note")
            put("status", "active")
            put("capture_kind", "manual")
            put("created_at", 1000)
            put("updated_at", 2000)
        })
        db.insertOrThrow("tb_reading_note_blocks", null, ContentValues().apply {
            put("id", UUID.randomUUID().toString())
            put("note_id", noteId)
            put("block_type", "text")
            put("content", "Visible body")
            put("sort_order", 1)
            put("created_at", 1000)
            put("updated_at", 2000)
        })
        db.insertOrThrow("tb_reading_note_blocks", null, ContentValues().apply {
            put("id", UUID.randomUUID().toString())
            put("note_id", noteId)
            put("block_type", "ai")
            put("content", "AI fixture summary")
            put("sort_order", 3)
            put("origin", "ai")
            put("created_at", 1000)
            put("updated_at", 2000)
        })
        val workspace = ReadingNoteWorkspaceStore(store)
        workspace.save(noteId, "Workspace UI note", "Old body", emptyList(), "active", false)
        workspace.save(noteId, "Workspace UI note", "Visible body", emptyList(), "active", false)
        var activity: android.app.Activity? = null
        var createdNoteId: String? = null
        try {
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            click(localized.getString(R.string.home_me))
            click(localized.getString(R.string.reading_notes))
            assertNotNull(waitFor("Workspace UI note"))
            val listLabels = mutableListOf<String>()
            collectLabels(instrumentation.uiAutomation.rootInActiveWindow, listLabels)
            assertEquals(1, listLabels.count { it == "Visible body" })
            click(localized.getString(R.string.note_edit))
            assertNotNull(waitFor(localized.getString(R.string.note_save_version)))
            assertNotNull(waitFor("Visible body"))
            setField(localized.getString(R.string.note_new_checklist_item), "Device checklist")
            hideKeyboard(instrumentation, activity!!)
            tap(localized.getString(R.string.note_add_checklist))
            waitUntil { workspace.blocks(noteId).any {
                it.type == "checklist" && it.content == "Device checklist"
            } }
            click(localized.getString(R.string.note_edit_checklist))
            setField(localized.getString(R.string.note_checklist_item), "Edited on device")
            clickLast(localized.getString(R.string.save))
            waitUntil { workspace.blocks(noteId).any {
                it.type == "checklist" && it.content == "Edited on device"
            } }
            click(localized.getString(R.string.note_delete_checklist))
            clickDialogAction(localized.getString(R.string.note_delete_checklist))
            waitUntil { workspace.blocks(noteId).none { it.type == "checklist" } }
            assertNotNull(waitFor("AI fixture summary"))
            click(localized.getString(R.string.note_copy_ai_to_body))
            assertNotNull(waitFor("Visible body\n\nAI fixture summary"))
            click(localized.getString(R.string.cancel))
            waitUntil {
                find(instrumentation.uiAutomation.rootInActiveWindow,
                    localized.getString(R.string.note_save_version)) == null
            }
            SystemClock.sleep(500)
            assertEquals(1, store.libraryNotes().count { it.workspaceId == noteId })
            assertNotNull(waitFor("Workspace UI note"))
            assertNotNull(waitFor(localized.getString(R.string.note_select, "Workspace UI note")))
            click(localized.getString(R.string.note_edit))
            click(localized.getString(R.string.note_history))
            assertNotNull(waitFor(localized.getString(R.string.note_version_number, 1)))
            click(localized.getString(R.string.note_version_number, 1))
            assertNotNull(waitFor("Old body"))
            clickLast(localized.getString(R.string.note_restore_version))
            waitUntil { store.libraryNotes().any { it.workspaceId == noteId && it.readerNote == "Old body" } }
            waitUntil { find(instrumentation.uiAutomation.rootInActiveWindow,
                localized.getString(R.string.note_restore_version)) == null }
            assertNotNull(waitFor("Old body"))
            click(localized.getString(R.string.cancel))
            waitUntil { find(instrumentation.uiAutomation.rootInActiveWindow,
                localized.getString(R.string.note_save_version)) == null }
            click(localized.getString(R.string.note_new))
            SystemClock.sleep(300)
            clickLast("Workspace UI fixture")
            assertNotNull(waitFor(localized.getString(R.string.note_new)))
            assertNotNull(waitFor(localized.getString(R.string.note_title)))
            setField(localized.getString(R.string.note_title), "Created on device")
            setField(localized.getString(R.string.note_body), "Device body")
            assertNotNull(waitFor("Created on device"))
            assertNotNull(waitFor("Device body"))
            SystemClock.sleep(300)
            click(localized.getString(R.string.save))
            waitUntil { find(instrumentation.uiAutomation.rootInActiveWindow,
                localized.getString(R.string.note_title)) == null }
            assertNotNull(waitFor("Created on device"))
            val created = store.libraryNotes().single { it.book.id == bookId && it.title == "Created on device" }
            createdNoteId = created.workspaceId
            assertEquals("Device body", created.readerNote)
            hideKeyboard(instrumentation, activity!!)
            tap(localized.getString(R.string.note_select, "Created on device"))
            assertNotNull(waitFor(localized.getString(R.string.note_selected, 1)))
            click(localized.getString(R.string.note_archive))
            waitUntil {
                store.libraryNotes().any { it.workspaceId == createdNoteId && it.status == "archived" }
            }
        } finally {
            activity?.finish()
            createdNoteId?.let { ReadingNoteWorkspaceStore(store).deletePermanently(it) }
            ReadingNoteWorkspaceStore(store).deletePermanently(noteId)
            db.delete("tb_books", "id = ?", arrayOf(bookId.toString()))
            store.close()
        }
    }

    private fun click(label: String) {
        val node = waitFor(label)
        assertNotNull("Missing $label", node)
        assertTrue("Cannot click $label", performOnClickable(node!!, AccessibilityNodeInfo.ACTION_CLICK))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(250, 3_000)
    }

    private fun clickLast(label: String) {
        val matches = mutableListOf<AccessibilityNodeInfo>()
        findAll(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow,
            label, matches)
        assertTrue("Missing menu item $label", matches.isNotEmpty())
        assertTrue("Cannot click menu item $label", performOnClickable(matches.last(),
            AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun clickDialogAction(label: String) {
        var matches = emptyList<AccessibilityNodeInfo>()
        waitUntil {
            matches = mutableListOf<AccessibilityNodeInfo>().also {
                findAll(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow,
                    label, it)
            }
            matches.count { it.text?.toString() == label } >= 2
        }
        val action = matches.last { it.text?.toString() == label }
        assertTrue("Cannot click dialog action $label", performOnClickable(action,
            AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun tap(label: String) {
        val node = waitFor(label)!!
        val bounds = Rect().also(node::getBoundsInScreen)
        Log.i("NotesUiTest", "tap $label at $bounds")
        assertTrue("No visible bounds for $label: $bounds", !bounds.isEmpty)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val time = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN,
            bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
        val up = MotionEvent.obtain(time, time + 80, MotionEvent.ACTION_UP,
            bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        up.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            assertTrue(automation.injectInputEvent(down, true))
            assertTrue(automation.injectInputEvent(up, true))
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    private fun hideKeyboard(instrumentation: android.app.Instrumentation, activity: android.app.Activity) {
        instrumentation.runOnMainSync {
            val input = activity.getSystemService(InputMethodManager::class.java)
            input.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
            activity.currentFocus?.clearFocus()
        }
        SystemClock.sleep(250)
    }

    private fun setField(label: String, value: String) {
        var current: AccessibilityNodeInfo? = waitFor(label)
        while (current != null && !current.isEditable) current = current.parent
        assertNotNull("Missing editable field $label", current)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        assertTrue("Cannot set $label", current!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
    }

    private fun performOnClickable(node: AccessibilityNodeInfo, action: Int): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if ((action == AccessibilityNodeInfo.ACTION_LONG_CLICK && current.isLongClickable) ||
                (action == AccessibilityNodeInfo.ACTION_CLICK && current.isClickable)) {
                if (current.performAction(action)) return true
            }
            current = current.parent
        }
        return false
    }

    private fun waitFor(label: String): AccessibilityNodeInfo? {
        repeat(50) {
            find(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow, label)?.let {
                return it
            }
            SystemClock.sleep(100)
        }
        val labels = mutableListOf<String>()
        collectLabels(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow, labels)
        throw AssertionError("Missing expected label: $label; visible=${labels.take(40)}")
    }

    private fun collectLabels(node: AccessibilityNodeInfo?, labels: MutableList<String>) {
        if (node == null) return
        node.text?.toString()?.takeIf(String::isNotBlank)?.let(labels::add)
        node.contentDescription?.toString()?.takeIf(String::isNotBlank)?.let(labels::add)
        for (index in 0 until node.childCount) collectLabels(node.getChild(index), labels)
    }

    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (index in 0 until node.childCount) find(node.getChild(index), label)?.let { return it }
        return null
    }

    private fun findAll(node: AccessibilityNodeInfo?, label: String,
                        matches: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) matches += node
        for (index in 0 until node.childCount) findAll(node.getChild(index), label, matches)
    }

    private fun waitUntil(condition: () -> Boolean) {
        repeat(50) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        assertTrue("UI did not reach the expected state", condition())
    }
}
