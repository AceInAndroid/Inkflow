package com.aceinandroid.inkflow

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File
import java.io.IOException
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

internal data class WebDavResource(
    val name: String,
    val directory: Boolean,
    val size: Long?,
    val modifiedAt: Long?,
    val etag: String? = null,
)

internal sealed class WebDavWriteCondition {
    object Absent : WebDavWriteCondition()
    data class Match(val etag: String) : WebDavWriteCondition()
}

internal interface WebDavClient {
    fun resources(path: String, depth: Int = 1): List<WebDavResource>
    fun ensureDirectory(path: String)
    fun upload(path: String, file: File, condition: WebDavWriteCondition? = null)
    fun download(path: String, target: File, maxBytes: Long = 4L * 1024 * 1024 * 1024)
}

internal class WebDavTransport(
    endpoint: String,
    username: String,
    password: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .followRedirects(false)
        .build(),
) : WebDavClient {
    private val base = requireNotNull(endpoint.toHttpUrlOrNull()) { "Invalid WebDAV address" }.also {
        require(it.scheme == "https" && it.host.isNotBlank() && it.query == null &&
            it.fragment == null && it.username.isEmpty() && it.password.isEmpty()) {
            "WebDAV requires an HTTPS address without embedded credentials"
        }
    }
    private val authorization = Credentials.basic(username, password, Charsets.UTF_8)

    override fun resources(path: String, depth: Int): List<WebDavResource> {
        require(depth in 0..1)
        val request = request(path).header("Depth", depth.toString())
            .method("PROPFIND", RequestBody.create(null, ByteArray(0))).build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return emptyList()
            if (response.code != 207) throw failure("PROPFIND", response.code)
            val xml = response.body?.byteStream()?.use { it.readBounded(MAX_PROPFIND_BYTES) }
                ?: throw IOException("Empty or oversized WebDAV response")
            val document = Jsoup.parse(xml.toString(Charsets.UTF_8), "", Parser.xmlParser())
            return document.getAllElements().filter { it.localName() == "response" }.mapNotNull { node ->
                val href = node.children().firstOrNull { it.localName() == "href" }?.text()
                    ?: return@mapNotNull null
                val property = node.children().filter { it.localName() == "propstat" }
                    .firstOrNull { status ->
                        status.children().firstOrNull { it.localName() == "status" }
                            ?.text()?.contains(" 200 ") == true
                    }?.children()?.firstOrNull { it.localName() == "prop" }
                    ?: return@mapNotNull null
                val name = runCatching { URI(href).path.trimEnd('/').substringAfterLast('/') }
                    .getOrNull()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val directory = property.getAllElements().any { it.localName() == "collection" }
                val size = property.children().firstOrNull { it.localName() == "getcontentlength" }
                    ?.text()?.toLongOrNull()
                val modified = property.children().firstOrNull { it.localName() == "getlastmodified" }
                    ?.text()?.let(::parseHttpDate)
                val etag = property.children().firstOrNull { it.localName() == "getetag" }
                    ?.text()?.takeIf(String::isNotBlank)
                WebDavResource(name, directory, size, modified, etag)
            }
        }
    }

    fun createDirectory(path: String) {
        val request = request(path).method("MKCOL", RequestBody.create(null, ByteArray(0))).build()
        client.newCall(request).execute().use { response ->
            if (response.code !in 200..299 && response.code != 405)
                throw failure("MKCOL", response.code)
        }
    }

    override fun ensureDirectory(path: String) {
        val parts = pathParts(path)
        for (index in parts.indices) createDirectory(parts.take(index + 1).joinToString("/"))
    }

    override fun upload(path: String, file: File, condition: WebDavWriteCondition?) {
        require(file.isFile) { "Upload file is missing" }
        val body = RequestBody.create("application/octet-stream".toMediaType(), file)
        val request = request(path).put(body)
        when (condition) {
            WebDavWriteCondition.Absent -> request.header("If-None-Match", "*")
            is WebDavWriteCondition.Match -> request.header("If-Match", condition.etag)
            null -> Unit
        }
        client.newCall(request.build()).execute().use { response ->
            if (response.code == 412) throw IOException("Remote library changed during sync (HTTP 412)")
            if (response.code !in 200..299) throw failure("PUT", response.code)
        }
    }

    override fun download(path: String, target: File, maxBytes: Long) {
        require(maxBytes > 0 && !target.exists()) { "Invalid download target" }
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.part")
        try {
            client.newCall(request(path).get().build()).execute().use { response ->
                if (response.code != 200) throw failure("GET", response.code)
                val body = response.body ?: throw IOException("Empty WebDAV file")
                if (body.contentLength() > maxBytes) throw IOException("WebDAV file is too large")
                body.byteStream().use { input -> temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > maxBytes) throw IOException("WebDAV file is too large")
                        output.write(buffer, 0, count)
                    }
                } }
            }
            if (!temporary.renameTo(target)) throw IOException("Cannot finish WebDAV download")
        } finally {
            temporary.delete()
        }
    }

    private fun request(path: String): Request.Builder {
        val url = base.newBuilder()
        pathParts(path).forEach(url::addPathSegment)
        return Request.Builder().url(url.build()).header("Authorization", authorization)
            .header("Accept-Charset", "utf-8")
    }

    private fun pathParts(path: String): List<String> = path.trim('/').split('/').also { parts ->
        require(parts.isNotEmpty() && parts.all {
            it.isNotBlank() && it != "." && it != ".." && '\\' !in it
        }) { "Invalid WebDAV path" }
    }

    private fun parseHttpDate(value: String): Long? = runCatching {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
            isLenient = false
        }.parse(value)?.time
    }.getOrNull()

    private fun Element.localName(): String = normalName().substringAfter(':')

    private fun failure(method: String, code: Int): IOException =
        IOException("WebDAV $method failed (HTTP $code)")

    private companion object {
        const val MAX_PROPFIND_BYTES = 4 * 1024 * 1024
    }
}
