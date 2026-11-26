package com.aceinandroid.inkflow

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BookshelfGroupsUiTest {
    @Test fun createsAndRenamesFolderFromBookshelf() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val context = instrumentation.targetContext
        val store = BookStore(context)
        val original = "Folder ${UUID.randomUUID()}"
        val renamed = "Renamed ${UUID.randomUUID()}"
        val child = "Child ${UUID.randomUUID()}"
        var activity: android.app.Activity? = null
        try {
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            click(context.getString(R.string.group_create))
            setDialogText(original)
            click(context.getString(R.string.save))
            waitUntil { store.groups().any { it.name == original } }
            assertTrue(waitFor(original) != null)

            click(context.getString(R.string.group_options))
            click(context.getString(R.string.group_rename))
            setDialogText(renamed)
            click(context.getString(R.string.save))
            waitUntil { store.groups().any { it.name == renamed } }
            assertTrue(waitFor(renamed) != null)

            click(context.getString(R.string.group_create))
            setDialogText(child)
            click(context.getString(R.string.save))
            waitUntil { store.groups().any { it.name == child } }
            click(context.getString(R.string.group_options))
            click(context.getString(R.string.group_move_folder))
            click(context.getString(R.string.group_top_level))
            waitUntil { store.groups().single { it.name == child }.parentId == 0L }
        } finally {
            store.groups().filter { it.name == child || it.name == original || it.name == renamed }
                .forEach { store.deleteGroup(it.id) }
            activity?.finish()
            store.close()
        }
    }

    private fun setDialogText(value: String) {
        assertTrue("Folder dialog missing", waitFor(
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.group_name)) != null)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val node = waitForClass("android.widget.EditText")
        if (!node.isFocused) {
            assertTrue("Cannot focus folder input", node.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
        }
        waitUntil("Folder input did not gain focus") {
            findClass(instrumentation.uiAutomation.rootInActiveWindow, "android.widget.EditText")
                ?.let { it.refresh() && it.isFocused } == true
        }
        instrumentation.waitForIdleSync()
        // Focus can replace the Compose accessibility node. Send once to the current node, then
        // wait for the exact value to propagate; ACTION_SET_TEXT accepting is not a state assertion.
        val focused = waitForClass("android.widget.EditText")
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        assertTrue("Cannot enter folder name", focused.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
        val deadline = SystemClock.uptimeMillis() + 10_000
        var entered: String? = null
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            val current = findClass(instrumentation.uiAutomation.rootInActiveWindow, "android.widget.EditText")
            if (current?.refresh() == true) entered = current.text?.toString()
            if (entered == value) return
            SystemClock.sleep(50)
        }
        assertEquals("Folder input did not retain text", value, entered)
    }

    private fun click(label: String) {
        val node = waitFor(label) ?: error("Missing $label")
        var clickable: AccessibilityNodeInfo? = node
        while (clickable != null && !clickable.isClickable) clickable = clickable.parent
        assertTrue("Cannot click $label", clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }

    private fun waitFor(label: String): AccessibilityNodeInfo? {
        repeat(100) {
            find(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow, label)
                ?.let { return it }
            SystemClock.sleep(100)
        }
        return null
    }

    private fun waitForClass(className: String): AccessibilityNodeInfo {
        repeat(100) {
            findClass(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow,
                className)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Missing $className")
    }

    private fun waitUntil(message: String = "Folder update did not complete", condition: () -> Boolean) {
        repeat(100) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        error(message)
    }

    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (index in 0 until node.childCount) find(node.getChild(index), label)?.let { return it }
        return null
    }

    private fun findClass(node: AccessibilityNodeInfo?, className: String): AccessibilityNodeInfo? {
        if (node == null) return null
        var last = if (node.className?.toString() == className) node else null
        for (index in 0 until node.childCount) {
            findClass(node.getChild(index), className)?.let { last = it }
        }
        return last
    }
}
