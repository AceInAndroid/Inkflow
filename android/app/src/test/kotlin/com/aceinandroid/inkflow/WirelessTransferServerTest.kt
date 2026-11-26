package com.aceinandroid.inkflow

import com.aceinandroid.inkflow.transfer.TransferFailure
import com.aceinandroid.inkflow.transfer.TransferRequest
import com.aceinandroid.inkflow.transfer.WirelessTransferServer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

class WirelessTransferServerTest {
    private fun request(text: String) = TransferRequest.read(text.byteInputStream())
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: TransferFailure) { }
    }

    @Test fun rejectsTraversalUnsupportedNamesAndControlCharacters() {
        listOf("../x.epub", "a/b.epub", "a\\b.epub", "x:book.epub", ".book.epub", "book.exe",
            "x\u0000.txt", "x\n.txt", "a".repeat(241) + ".txt").forEach {
            rejects { TransferRequest.fileName(URLEncoder.encode(it, "UTF-8")) }
        }
        rejects { TransferRequest.fileName("%GG.epub") }
        assertEquals("中文 + book.EPUB", TransferRequest.fileName("%E4%B8%AD%E6%96%87%20%2B%20book.EPUB"))
        assertEquals("a+b.txt", TransferRequest.fileName("a+b.txt"))
    }

    @Test fun rejectsAmbiguousUnboundedOrMalformedHttp() {
        listOf(
            "POST / HTTP/1.1\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\n",
            "POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n",
            "GET / HTTP/1.1\n\n",
            "GET / HTTP/1.1\r\nX-Test: " + "x".repeat(5000) + "\r\n\r\n",
            "GET / HTTP/1.1\r\nBad Header: 1\r\n\r\n",
        ).forEach { text -> rejects { request(text) } }
        rejects { request("POST / HTTP/1.1\r\nContent-Length: -1\r\n\r\n").contentLength }
        rejects { request("POST / HTTP/1.1\r\n\r\n").contentLength }
    }

    private fun withServer(importer: (File) -> Unit = {}, block: (WirelessTransferServer, URI, File) -> Unit) {
        val directory = Files.createTempDirectory("wireless-test").toFile()
        WirelessTransferServer.forTests(directory, importer).use { server ->
            try { block(server, URI(server.startOn(InetAddress.getLoopbackAddress())), directory) }
            finally { server.close(); directory.deleteRecursively() }
        }
    }

    private fun exchange(uri: URI, request: String, body: ByteArray = byteArrayOf()): String =
        Socket(uri.host, uri.port).use { socket ->
            socket.soTimeout = 5000
            socket.getOutputStream().apply { write(request.toByteArray(Charsets.US_ASCII)); write(body); flush() }
            socket.shutdownOutput()
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }

    private fun upload(uri: URI, name: String, body: ByteArray, extra: String = "", token: String = uri.path.drop(1),
        declared: Long = body.size.toLong()): String = exchange(uri,
        "POST ${uri.path}/upload?name=${URLEncoder.encode(name, "UTF-8")} HTTP/1.1\r\n" +
            "Host: ${uri.rawAuthority}\r\nX-Transfer-Token: $token\r\nContent-Type: application/octet-stream\r\n" +
            "Content-Length: $declared\r\n$extra\r\n", body)

    @Test fun browserPageAndSequentialBatchReuseExistingImporter() {
        val imported = CopyOnWriteArrayList<Pair<String, String>>()
        withServer({ imported += it.name to it.readText() }) { _, uri, _ ->
            val page = exchange(uri, "GET ${uri.path} HTTP/1.1\r\nHost: ${uri.rawAuthority}\r\n\r\n")
            assertTrue(page.startsWith("HTTP/1.1 200"))
            assertTrue(page.contains("type=\"file\" multiple"))
            assertTrue(page.contains("X-Transfer-Token"))
            assertTrue(upload(uri, "第一本.txt", "first".toByteArray()).startsWith("HTTP/1.1 201"))
            assertTrue(upload(uri, "second.md", "second".toByteArray()).startsWith("HTTP/1.1 201"))
            assertEquals(listOf("第一本.txt" to "first", "second.md" to "second"), imported.toList())
        }
    }

    @Test fun badTokenOriginAndOversizedOrPartialBodiesNeverImport() {
        var count = 0
        withServer({ count++ }) { _, uri, _ ->
            assertTrue(upload(uri, "a.txt", byteArrayOf(1), token = "wrong").startsWith("HTTP/1.1 403"))
            assertTrue(upload(uri, "a.txt", byteArrayOf(1), extra = "Origin: http://evil.example\r\n")
                .startsWith("HTTP/1.1 403"))
            assertTrue(upload(uri, "a.txt", byteArrayOf(), declared = WirelessTransferServer.MAX_FILE_BYTES + 1)
                .startsWith("HTTP/1.1 413"))
            assertTrue(upload(uri, "a.txt", byteArrayOf(1), declared = 10).startsWith("HTTP/1.1 400"))
            assertEquals(0, count)
        }
    }

    @Test fun failedImportDoesNotPreventNextFileAndSessionCountIsBounded() {
        var count = 0
        withServer({ if (it.name == "bad.txt") error("Invalid book"); count++ }) { _, uri, _ ->
            assertTrue(upload(uri, "bad.txt", byteArrayOf(1)).startsWith("HTTP/1.1 422"))
            repeat(19) { assertTrue(upload(uri, "$it.txt", byteArrayOf(1)).startsWith("HTTP/1.1 201")) }
            assertTrue(upload(uri, "last.txt", byteArrayOf(1)).startsWith("HTTP/1.1 413"))
            assertEquals(19, count)
        }
    }

    @Test fun stoppingClosesListenerAndRestartRotatesSessionSecret() {
        withServer { server, uri, _ ->
            server.close()
            val next = URI(server.startOn(InetAddress.getLoopbackAddress()))
            assertNotEquals(uri.path, next.path)
            val oldPath = exchange(next, "GET ${uri.path} HTTP/1.1\r\nHost: ${next.rawAuthority}\r\n\r\n")
            assertTrue(oldPath.startsWith("HTTP/1.1 404"))
        }
    }
}
