package com.aceinandroid.inkflow

import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IncomingBookIntentTest {
    @Test fun multipleSharedBooksKeepOrderAndIgnoreDuplicateClipItems() {
        val first = Uri.parse("content://books/first.epub")
        val second = Uri.parse("content://books/second.epub")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second))
            clipData = ClipData.newRawUri("first", first).apply {
                addItem(ClipData.Item(second))
            }
        }
        assertEquals(listOf(first, second), incomingBookUris(intent))
    }

    @Test fun singleShareCanUseClipDataAndViewUsesData() {
        val book = Uri.parse("content://books/single.fb2")
        assertEquals(listOf(book), incomingBookUris(Intent(Intent.ACTION_SEND).apply {
            clipData = ClipData.newRawUri("book", book)
        }))
        assertEquals(listOf(book), incomingBookUris(Intent(Intent.ACTION_VIEW, book)))
    }

    @Test fun activityAcceptsMultipleShareIntents() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/epub+zip"
            addCategory(Intent.CATEGORY_DEFAULT)
            setPackage(context.packageName)
        }
        assertTrue(context.packageManager.queryIntentActivities(intent,
            PackageManager.MATCH_DEFAULT_ONLY).any { it.activityInfo.name == MainActivity::class.java.name })
    }
}
