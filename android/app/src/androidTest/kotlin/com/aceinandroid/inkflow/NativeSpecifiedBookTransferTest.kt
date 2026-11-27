package com.aceinandroid.inkflow

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aceinandroid.inkflow.core.ReaderEngineMode
import com.aceinandroid.inkflow.core.sync.SyncGate
import com.aceinandroid.inkflow.transfer.WirelessTransferServer
import com.aceinandroid.inkflow.ui.reader.ReaderScreen
import com.aceinandroid.inkflow.ui.theme.InkflowTheme
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in original-book acceptance. Source stays in the app's private cache and is never committed. */
@RunWith(AndroidJUnit4::class)
class NativeSpecifiedBookTransferTest {
    @Test fun specifiedEpubTransfersWithoutByteChangesAndSupportsSearchAndCfi() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Specified-book transfer requires an explicit opt-in", args.getString("exerciseSpecifiedBook") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "validation-book.epub")
        require(source.isFile && source.length() in 1..100_000_000) { "Private validation-book.epub is required" }
        val expectedHash = requireNotNull(args.getString("sourceSha256"))
        val fromHost = args.getString("receiveFromHost") == "true"
        val exerciseUi = args.getString("exerciseSpecifiedBookUi") == "true"
        val addressFile = File(context.cacheDir, "validation-transfer-address.txt")
        require(expectedHash.matches(Regex("[a-f0-9]{64}")))
        assertEquals("Host-to-device transfer changed the supplied file", expectedHash, sha256(source))
        val lease = checkNotNull(NativeSyncIntegration.gate.tryAcquire(SyncGate.Trigger.LOCAL_MUTATION))
        BookStore(context).use { store ->
            val before = store.all().map { it.id }.toSet()
            var imported: StoredBook? = null
            try {
                // Release the fixture-setup lease before the server takes its normal mutation lease.
                lease.close()
                val importedEvents = java.util.concurrent.CountDownLatch(2)
                WirelessTransferServer(context, store) { event -> if (event.imported) importedEvents.countDown() }.use { server ->
                    val address = URI(if (fromHost) server.start() else server.startOn(InetAddress.getByName("127.0.0.1")))
                    if (fromHost) {
                        addressFile.writeText(address.toASCIIString())
                        assertTrue("Host must send the specified book twice over WiFi", importedEvents.await(120, java.util.concurrent.TimeUnit.SECONDS))
                    } else repeat(2) {
                        val response = Socket(address.host, address.port).use { socket ->
                            socket.soTimeout = 120_000
                            val header = "POST ${address.path}/upload?name=validation-book.epub HTTP/1.1\r\n" +
                                "Host: ${address.rawAuthority}\r\nX-Transfer-Token: ${address.path.drop(1)}\r\n" +
                                "Content-Type: application/octet-stream\r\nContent-Length: ${source.length()}\r\n\r\n"
                            socket.getOutputStream().apply {
                                write(header.toByteArray(Charsets.UTF_8)); source.inputStream().use { it.copyTo(this) }; flush()
                            }
                            socket.shutdownOutput()
                            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
                        }
                        assertTrue("WiFi upload was rejected", response.startsWith("HTTP/1.1 201"))
                    }
                }
                val matches = store.all().filter { it.format == "epub" && sha256(it.file) == expectedHash }
                assertEquals("Duplicate upload must keep one library entry", 1, matches.size)
                imported = matches.single()
                val book = matches.single()
                val document = BookParser.inspect(book.file, book.title)
                assertTrue("Book must expose a usable spine", document.chapters.isNotEmpty())
                assertTrue("Book must expose a table of contents", document.toc.isNotEmpty())
                val chapterIndex = document.chapters.indexOfFirst {
                    BookParser.readEpubText(book.file, it).text.length > 1000
                }
                assertTrue("Book must expose narrative text", chapterIndex >= 0)
                val chapter = document.chapters[chapterIndex]
                val text = BookParser.readEpubText(book.file, chapter)
                val offset = (text.text.length / 2).coerceAtMost(text.text.length - 8)
                val cfi = requireNotNull(text.cfiAt(requireNotNull(chapter.baseCfi), offset))
                assertEquals(chapterIndex, EpubCfi.chapterIndex(cfi, document.chapters))
                assertEquals(offset, text.offsetForCfi(cfi))
                val query = text.text.substring(offset, offset + 8).trim()
                assertTrue(query.isNotBlank())
                assertTrue("Search must resolve to real book source", BookSearcher.searchEpub(book.file, document, query)
                    .any { hit -> BookParser.readEpubText(book.file, document.chapters[hit.chapterIndex]).text
                        .regionMatches(hit.offset, query, 0, query.length, true) })
                // A private imported fixture can be restored at an exact CFI without altering an existing book.
                if (exerciseUi) assertTrue("UI validation requires a newly imported fixture to protect existing reading progress", book.id !in before)
                if (book.id !in before) {
                    store.savePosition(book.id, chapterIndex, cfi, (chapterIndex + offset.toDouble() / text.text.length) / document.chapters.size)
                    val restored = store.all().single { it.id == book.id }
                    assertEquals(cfi, restored.cfi)
                    if (exerciseUi) openRestoredBookUi(context, store, restored, chapter.title, text.text, offset, cfi)
                }
                val transport = if (fromHost) "host HTTP upload" else "loopback HTTP upload"
                println("Specified EPUB: $transport bytes/hash, duplicate identity, spine, TOC, search and exact CFI passed; chapters=${document.chapters.size}")
            } finally {
                lease.close()
                imported?.takeIf { it.id !in before }?.let { store.removeBook(it.id) }
                addressFile.delete()
                source.delete()
            }
        }
    }

    private fun openRestoredBookUi(
        context: Context, store: BookStore, restored: StoredBook, chapterTitle: String,
        chapterText: String, savedOffset: Int, savedCfi: String,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val controls = ReaderControlsStore(context)
        val preferences = context.getSharedPreferences("native_reader_appearance", Context.MODE_PRIVATE)
        val previousEngine = preferences.getString("reader_engine_mode", null)
        val usageBefore = usageState(context)
        val screenshot = File(context.filesDir, "specified-book-validation/reader.png")
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            assertEquals("Reader must receive the exact restored CFI", savedCfi, restored.cfi)
            controls.setReaderEngineMode(ReaderEngineMode.NATIVE_PAGED)
            assertEquals(ReaderEngineMode.NATIVE_PAGED, controls.readerEngineMode())
            assertTrue("Cannot clear a stale validation screenshot", !screenshot.exists() || screenshot.delete())
            val launched = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            scenario = launched
            launched.onActivity { activity ->
                activity.setContent {
                    InkflowTheme {
                        ReaderScreen(book = restored, store = store, snackbar = remember { SnackbarHostState() },
                            initialOffset = null, onBack = {})
                    }
                }
            }
            var stableSince = 0L
            waitUntil("real source page containing the exact restored character offset") {
                var ready = false
                launched.onActivity { activity ->
                    val decor = activity.window.decorView
                    ready = decor.hasWindowFocus() && activity.readerKeyHandler != null &&
                        NativeSyncIntegration.gate.state().activity == SyncGate.Activity.ACTIVE_READING &&
                        hasSourcePage(decor, chapterTitle, chapterText, savedOffset)
                }
                if (!ready) { stableSince = 0L; false }
                else {
                    val actual = store.all().single { it.id == restored.id }
                    assertEquals("Opening the reader must preserve the exact CFI", savedCfi, actual.cfi)
                    assertEquals("Opening the reader must preserve the restored chapter", restored.position, actual.position)
                    assertEquals("Ordinary book opening must not change model usage", usageBefore, usageState(context))
                    if (stableSince == 0L) stableSince = SystemClock.uptimeMillis()
                    SystemClock.uptimeMillis() - stableSince >= 500
                }
            }
            capturePrivateReader(launched, screenshot)
            assertEquals(savedCfi, store.all().single { it.id == restored.id }.cfi)
            assertEquals(usageBefore, usageState(context))
            // Closing also exercises the reader's lifecycle position flush before fixture cleanup.
            launched.close()
            scenario = null
            instrumentation.waitForIdleSync()
            waitUntil("reader lifecycle position flush completed") {
                NativeSyncIntegration.gate.state().activity != SyncGate.Activity.ACTIVE_READING
            }
            assertEquals("Closing the reader must preserve the exact saved CFI", savedCfi,
                store.all().single { it.id == restored.id }.cfi)
            assertEquals("Opening and closing ordinary reading must not change model usage", usageBefore, usageState(context))
            println("Specified EPUB actual native-paged ReaderScreen, source semantics, exact CFI and unchanged model usage passed; private screenshot=${screenshot.absolutePath}")
        } finally {
            try { scenario?.close() }
            finally {
                val editor = preferences.edit()
                if (previousEngine == null) editor.remove("reader_engine_mode") else editor.putString("reader_engine_mode", previousEngine)
                check(editor.commit()) { "Cannot restore reader engine preference" }
                assertEquals(previousEngine, preferences.getString("reader_engine_mode", null))
            }
        }
    }

    /** Read only app-owned Compose semantics on main; Text is the visible page, never EditableText's full chapter. */
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun hasSourcePage(view: View, chapterTitle: String, chapterText: String, savedOffset: Int): Boolean {
        if (view is ViewRootForTest && view.isShown && !view.hasPendingMeasureOrLayout) {
            fun visit(node: SemanticsNode): Boolean {
                val description = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                    .firstOrNull { it.startsWith("$chapterTitle, ") }
                if (description != null && node.boundsInWindow.width > 0 && node.boundsInWindow.height > 0) {
                    val numbers = description.removePrefix("$chapterTitle, ").split('/')
                    val page = numbers.getOrNull(0)?.toIntOrNull()
                    val total = numbers.getOrNull(1)?.toIntOrNull()
                    val excerpt = node.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text
                    if (page != null && total != null && page in 1..total && !excerpt.isNullOrBlank()) {
                        val start = chapterText.lastIndexOf(excerpt, savedOffset)
                        if (start >= 0 && savedOffset < start + excerpt.length &&
                            excerpt.any { !it.isWhitespace() && it != '\uFFFC' }) return true
                    }
                }
                return node.children.any(::visit)
            }
            if (visit(view.semanticsOwner.unmergedRootSemanticsNode)) return true
        }
        return view is ViewGroup && (0 until view.childCount).any {
            hasSourcePage(view.getChildAt(it), chapterTitle, chapterText, savedOffset)
        }
    }

    private fun usageState(context: Context): Map<String, Any?> {
        val values = context.getSharedPreferences("native_ai_workspace", Context.MODE_PRIVATE).all
        return listOf("token_usage", "usage_journal_v2", "legacy_usage_snapshot", "usage_accounting_incomplete")
            .associateWith { values[it] }
    }

    /** PixelCopy captures this Activity's actual window, and writes only to app-private storage. */
    private fun capturePrivateReader(scenario: ActivityScenario<MainActivity>, target: File) {
        val copied = CountDownLatch(1)
        val result = AtomicInteger(-1)
        var bitmap: Bitmap? = null
        try {
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                assertTrue("Reader window must be visible for the screenshot", decor.isShown && decor.width > 0 && decor.height > 0)
                val image = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                bitmap = image
                PixelCopy.request(activity.window, image, { status ->
                    result.set(status)
                    copied.countDown()
                }, Handler(Looper.getMainLooper()))
            }
            assertTrue("Reader screenshot timed out", copied.await(10, TimeUnit.SECONDS))
            assertEquals("Reader screenshot failed", PixelCopy.SUCCESS, result.get())
            assertTrue("Cannot create private screenshot directory", target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
            target.outputStream().use { output ->
                assertTrue("Cannot encode private screenshot", requireNotNull(bitmap).compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            assertTrue("Private screenshot is empty", target.length() > 0)
        } finally { bitmap?.recycle() }
    }

    private fun waitUntil(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        // Never include source paragraphs or dump the accessibility tree in failure output.
        throw AssertionError("Timed out waiting for $description")
    }

    private fun sha256(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) { val count = input.read(bytes); if (count < 0) break; hash.update(bytes, 0, count) }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
}
