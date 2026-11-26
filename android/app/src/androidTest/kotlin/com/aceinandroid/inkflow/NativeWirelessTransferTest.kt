package com.aceinandroid.inkflow

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aceinandroid.inkflow.transfer.WirelessTransferServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NativeWirelessTransferTest {
    @Test fun actualSocketBatchImportsBooksThroughTheLibraryBoundary() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = BookStore(context)
        val name = "Wireless ${UUID.randomUUID()}"
        val imported = mutableListOf<Long>()
        NativeSyncIntegration.readingSaved()
        try {
            WirelessTransferServer(context, store).use { server ->
                val uri = URI(server.startOn(InetAddress.getByName("127.0.0.1")))
                for (index in 1..2) {
                    val title = "$name $index"
                    val bytes = "Chapter 1\n$title. An original transfer fixture.".toByteArray()
                    val response = Socket(uri.host, uri.port).use { socket ->
                        socket.soTimeout = 10_000
                        val header = "POST ${uri.path}/upload?name=${URLEncoder.encode("$title.txt", "UTF-8").replace("+", "%20")} HTTP/1.1\r\n" +
                            "Host: ${uri.rawAuthority}\r\nX-Transfer-Token: ${uri.path.drop(1)}\r\n" +
                            "Content-Type: application/octet-stream\r\nContent-Length: ${bytes.size}\r\n\r\n"
                        socket.getOutputStream().apply { write(header.toByteArray()); write(bytes); flush() }
                        socket.shutdownOutput()
                        socket.getInputStream().readBytes().toString(Charsets.UTF_8)
                    }
                    assertTrue(response, response.startsWith("HTTP/1.1 201"))
                    val book = store.all().single { it.title == title }
                    imported += book.id
                    assertEquals("txt", book.format)
                    assertTrue(book.file.readText().contains(title))
                }
            }
            assertEquals(2, imported.size)
        } finally {
            // Includes a fixture imported just before a failing response assertion.
            store.all().filter { it.title.startsWith(name) }.forEach { store.removeBook(it.id) }
            store.close()
        }
    }
}
