package com.aceinandroid.inkflow

import com.aceinandroid.inkflow.core.sync.VocabularyDelta
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Pure protocol fixtures: no device, database or credentials are required. */
class NativeIncrementalSyncBranchTest {
    private class Server(val entries: Map<String, String> = emptyMap()) : WebDavClient {
        var downloadFailure: String? = null
        var uploadFailure: String? = null
        var writes = 0
        override fun resources(path: String, depth: Int) = entries.map { (name, text) ->
            WebDavResource(name, false, text.toByteArray().size.toLong(), null, "etag")
        }
        override fun ensureDirectory(path: String) = Unit
        override fun download(path: String, target: File, maxBytes: Long) {
            target.writeText(entries.getValue(path.substringAfterLast('/')))
            downloadFailure?.let { throw IOException(it) }
            require(target.length() <= maxBytes)
        }
        override fun upload(path: String, file: File, condition: WebDavWriteCondition?) {
            uploadFailure?.let { throw IOException(it) }
            assertEquals("inkflow/data/vocabulary/v1/local.json", path)
            assertTrue(condition is WebDavWriteCondition.Absent)
            assertEquals("local", NativeIncrementalSyncPackage.decode(file.readText()).deviceId)
            writes++
        }
    }
    private fun packageData(device: String) = NativeIncrementalSyncPackage(VocabularyDelta.TYPE, device,
        100, mapOf("tb_vocabulary" to emptyList()))
    private fun fixture(test: (File) -> Unit) {
        val work = Files.createTempDirectory("incremental-branch-test").toFile()
        try { test(work) } finally { work.deleteRecursively() }
    }
    private fun run(server: Server, work: File, merge: (NativeIncrementalSyncPackage) -> Int = { 0 }) =
        NativeIncrementalSyncBranch(server, "local", work).synchronize("inkflow/data/vocabulary/v1", null,
            merge, { packageData("local") })

    @Test fun malformedPeerDoesNotPreventValidPeerOrOwnCapture() = fixture { work ->
        val server = Server(linkedMapOf("malformed.json" to "{", "peer.json" to packageData("peer").encode()))
        val merged = mutableListOf<String>()
        val report = run(server, work) { merged += it.deviceId; 0 }
        assertEquals(listOf("peer"), merged)
        assertEquals(IncrementalSyncReport(merged = 1, uploaded = 1, ignored = 1), report)
        assertEquals(1, server.writes)
        assertTrue(work.listFiles()!!.isEmpty())
    }

    @Test fun transportDownloadFailureEscapesAndCleansPartialFile() = fixture { work ->
        val server = Server(mapOf("peer.json" to packageData("peer").encode())).apply {
            downloadFailure = "connection reset"
        }
        try { run(server, work); fail("Network failure was swallowed") }
        catch (failure: IOException) { assertEquals("connection reset", failure.message) }
        assertEquals(0, server.writes)
        assertTrue(work.listFiles()!!.isEmpty())
    }

    @Test fun uploadFailureOnlyRecognizesExplicitPreconditionStatus() = fixture { work ->
        for (message in listOf("Remote library changed during sync (HTTP 412)", "WebDAV PUT failed: 412")) {
            val server = Server().apply { uploadFailure = message }
            assertEquals(1, run(server, work).conflicts)
        }
        for (message in listOf("HTTP 500", "connection to host 10.0.4.12 failed", "request 1412 failed")) {
            val server = Server().apply { uploadFailure = message }
            try { run(server, work); fail("Unrelated transport failure was treated as a conflict") }
            catch (failure: IOException) { assertEquals(message, failure.message) }
        }
        assertTrue(work.listFiles()!!.isEmpty())
    }
}
