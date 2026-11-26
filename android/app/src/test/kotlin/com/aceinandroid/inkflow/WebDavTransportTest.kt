package com.aceinandroid.inkflow

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class WebDavTransportTest {
    private fun client(reply: (okhttp3.Request) -> Response): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain -> reply(chain.request()) }).build()

    private fun response(request: okhttp3.Request, code: Int, body: String = ""): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Test")
            .body(body.toResponseBody("application/xml".toMediaType())).build()

    @Test fun listsFlutterWebDavDirectoryWithAuthenticatedPropfind() {
        val xml = """
            <d:multistatus xmlns:d="DAV:">
              <d:response><d:href>/dav/inkflow/data/file/</d:href><d:propstat>
                <d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
              <d:response><d:href>/dav/inkflow/data/file/book%20one.epub</d:href><d:propstat>
                <d:prop><d:getcontentlength>12</d:getcontentlength>
                <d:getlastmodified>Tue, 17 Nov 2026 12:00:00 GMT</d:getlastmodified>
                <d:getetag>&quot;book-v1&quot;</d:getetag></d:prop>
                <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
            </d:multistatus>
        """.trimIndent()
        val transport = WebDavTransport("https://example.invalid/dav", "reader", "secret",
            client { request ->
                assertEquals("PROPFIND", request.method)
                assertEquals("/dav/inkflow/data/file", request.url.encodedPath)
                assertEquals("1", request.header("Depth"))
                assertEquals("Basic cmVhZGVyOnNlY3JldA==", request.header("Authorization"))
                response(request, 207, xml)
            })
        val resources = transport.resources("inkflow/data/file")
        assertEquals(listOf("file", "book one.epub"), resources.map { it.name })
        assertTrue(resources[0].directory)
        assertFalse(resources[1].directory)
        assertEquals(12L, resources[1].size)
        assertTrue(resources[1].modifiedAt!! > 0)
        assertEquals("\"book-v1\"", resources[1].etag)
    }

    @Test fun databaseUploadUsesConditionalRequestsAndReportsConflict() {
        val source = File.createTempFile("webdav-database", ".db")
        try {
            source.writeText("database")
            val headers = mutableListOf<Pair<String?, String?>>()
            val transport = WebDavTransport("https://example.invalid/dav", "reader", "secret",
                client { request ->
                    headers += request.header("If-Match") to request.header("If-None-Match")
                    response(request, if (headers.size == 2) 412 else 201)
                })
            transport.upload("inkflow/database22.db", source, WebDavWriteCondition.Absent)
            val error = assertThrows(IOException::class.java) {
                transport.upload("inkflow/database22.db", source, WebDavWriteCondition.Match("\"db-v1\""))
            }
            assertEquals(listOf(null to "*", "\"db-v1\"" to null), headers)
            assertTrue(error.message!!.contains("changed"))
        } finally { source.delete() }
    }

    @Test fun createsDirectoriesAndUploadsFileToCompatiblePath() {
        val requests = mutableListOf<String>()
        val source = File.createTempFile("webdav-upload", ".epub")
        try {
            source.writeText("book contents")
            val transport = WebDavTransport("https://example.invalid/dav/", "reader", "secret",
                client { request ->
                    requests += "${request.method} ${request.url.encodedPath}"
                    if (request.method == "PUT") {
                        val buffer = Buffer()
                        request.body!!.writeTo(buffer)
                        assertEquals("book contents", buffer.readUtf8())
                    }
                    response(request, if (request.method == "MKCOL") 201 else 204)
                })
            transport.ensureDirectory("inkflow/data/file")
            transport.upload("inkflow/data/file/book one.epub", source)
            assertEquals(listOf("MKCOL /dav/inkflow", "MKCOL /dav/inkflow/data",
                "MKCOL /dav/inkflow/data/file", "PUT /dav/inkflow/data/file/book%20one.epub"), requests)
        } finally { source.delete() }
    }

    @Test fun downloadIsAtomicAndEnforcesSizeLimit() {
        val directory = File.createTempFile("webdav-download", "")
        directory.delete()
        directory.mkdirs()
        try {
            val transport = WebDavTransport("https://example.invalid/dav", "reader", "secret",
                client { request -> response(request, 200, "contents") })
            val tooSmall = File(directory, "small.db")
            assertThrows(IOException::class.java) {
                transport.download("inkflow/database22.db", tooSmall, maxBytes = 3)
            }
            assertFalse(tooSmall.exists())
            assertTrue(directory.listFiles().isNullOrEmpty())
            val target = File(directory, "database22.db")
            transport.download("inkflow/database22.db", target, maxBytes = 100)
            assertEquals("contents", target.readText())
        } finally { directory.deleteRecursively() }
    }

    @Test fun rejectsUnsafeAddressesAndPaths() {
        assertThrows(IllegalArgumentException::class.java) {
            WebDavTransport("http://example.invalid/dav", "reader", "secret")
        }
        val transport = WebDavTransport("https://example.invalid/dav", "reader", "secret",
            client { request -> response(request, 207) })
        assertThrows(IllegalArgumentException::class.java) { transport.resources("inkflow/../secret") }
    }
}
