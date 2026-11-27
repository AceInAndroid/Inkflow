package com.aceinandroid.inkflow.transfer

import android.content.Context
import android.net.Uri
import com.aceinandroid.inkflow.BookStore
import com.aceinandroid.inkflow.NativeSyncIntegration
import com.aceinandroid.inkflow.core.ai.SkillCodec
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Locale
import java.util.UUID

/** Foreground, explicitly started local listener. No Android service or background network work. */
internal class WirelessTransferServer private constructor(
    private val cacheDirectory: File,
    private val importer: (File) -> Unit,
    private val onEvent: (Event) -> Unit,
    private val skillReceiver: (File) -> Unit,
) : AutoCloseable {
    constructor(context: Context, store: BookStore, onEvent: (Event) -> Unit = {}) : this(
        File(context.applicationContext.cacheDir, "wireless-transfer"),
        { file -> NativeSyncIntegration.localMutation { store.import(Uri.fromFile(file)); Unit } }, onEvent,
        { file -> SkillTransferInbox(context).receive(file.name, file.readBytes()); Unit })

    data class Event(val name: String = "", val imported: Boolean = false, val error: String? = null,
        val stopped: Boolean = false, val skillPending: Boolean = false, val errorStatus: Int? = null)

    private class Session(val server: ServerSocket, val token: String, val host: String) {
        @Volatile var active = true
        @Volatile var client: Socket? = null
        var files = 0
        var bytes = 0L
    }
    private var session: Session? = null

    @Synchronized fun start(): String {
        session?.takeIf { it.active }?.let { return "http://${it.host}/${it.token}" }
        val address = wifiAddresses().firstOrNull() ?: throw IOException("Connect to a local Wi-Fi network first")
        return startOn(address)
    }

    /** Also used by JVM loopback tests; production only binds a Wi-Fi interface, never 0.0.0.0. */
    @Synchronized internal fun startOn(address: InetAddress): String {
        check(session?.active != true) { "Transfer is already running" }
        if (!cacheDirectory.isDirectory && !cacheDirectory.mkdirs()) throw IOException("Cannot prepare transfer cache")
        val socket = ServerSocket(0, 4, address)
        val current = Session(socket, UUID.randomUUID().toString().replace("-", ""),
            "${address.hostAddress}:${socket.localPort}")
        session = current
        Thread({ serve(current) }, "wireless-book-transfer").apply { isDaemon = true; start() }
        return "http://${current.host}/${current.token}"
    }

    @Synchronized override fun close() {
        session?.let {
            it.active = false
            runCatching { it.server.close() }
            runCatching { it.client?.close() }
        }
        session = null
    }

    private fun serve(current: Session) {
        try {
            while (current.active) {
                val socket = current.server.accept()
                current.client = socket
                socket.use {
                    if (!current.active) return@use
                    it.soTimeout = 15_000
                    try { handle(current, it) }
                    catch (failure: Exception) {
                        runCatching { respond(it, (failure as? TransferFailure)?.status ?: 400,
                            "text/plain; charset=utf-8", (failure.message ?: "Transfer failed").toByteArray(Charsets.UTF_8))
                            finishRejectedResponse(it)
                        }
                        if (current.active) notify(Event(error = failure.message ?: "Transfer failed", errorStatus = (failure as? TransferFailure)?.status))
                    }
                }
                current.client = null
            }
        } catch (_: IOException) {
            // Closing the listener is the normal stop path; the UI is notified on unexpected exits too.
        } finally {
            val unexpected = current.active
            current.active = false
            runCatching { current.server.close() }
            if (unexpected) notify(Event(stopped = true))
        }
    }

    /** Send the complete HTTP rejection before closing a socket with an unread request body.
     * A bare close can turn a useful 403/413 into a TCP reset for the uploading browser.
     * Bound both bytes and time; a rejected upload must never occupy the listener indefinitely.
     */
    private fun finishRejectedResponse(socket: Socket) {
        socket.shutdownOutput()
        val deadline = System.nanoTime() + 250_000_000L
        var remaining = 64 * 1024
        val buffer = ByteArray(8192)
        try {
            while (remaining > 0) {
                val millis = (deadline - System.nanoTime()) / 1_000_000
                if (millis <= 0) return
                socket.soTimeout = millis.toInt().coerceAtLeast(1)
                val count = socket.getInputStream().read(buffer, 0, minOf(remaining, buffer.size))
                if (count < 0) return
                remaining -= count
            }
        } catch (_: IOException) { /* The peer may finish, time out or disconnect after receiving the status. */ }
    }

    private fun handle(current: Session, socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
        val request = TransferRequest.read(input)
        if (request.headers["host"] != current.host) throw TransferFailure(403, "Invalid host")
        if (request.headers["origin"]?.let { it != "http://${current.host}" } == true)
            throw TransferFailure(403, "Invalid origin")
        if (request.method == "GET" && request.target == "/${current.token}") {
            respond(socket, 200, "text/html; charset=utf-8", page(current.token).toByteArray(Charsets.UTF_8))
            return
        }
        val skillUpload = request.target.startsWith("/${current.token}/skills?name=")
        if (request.method != "POST" || (!skillUpload && !request.target.startsWith("/${current.token}/upload?name=")))
            throw TransferFailure(404, "Not found")
        if (request.headers["x-transfer-token"] != current.token) throw TransferFailure(403, "Invalid session")
        if (request.headers["content-type"] != "application/octet-stream")
            throw TransferFailure(415, "Use a binary upload")
        val name = TransferRequest.fileName(request.target.substringAfter("?name="), skillUpload)
        val length = request.contentLength
        val limit = if (skillUpload) SkillCodec.MAX_FILE_BYTES.toLong() else MAX_FILE_BYTES
        if (length <= 0 || length > limit) throw TransferFailure(413, if (skillUpload)
            "Skill exceeds 64 KiB or is empty" else "Book exceeds 128 MiB or is empty")
        if (current.files >= MAX_FILES || length > MAX_SESSION_BYTES - current.bytes)
            throw TransferFailure(413, "Session limit reached; stop and start a new session")
        current.files++
        current.bytes += length
        val directory = File(cacheDirectory, UUID.randomUUID().toString())
        if (!directory.mkdir()) throw IOException("Cannot prepare upload")
        try {
            val file = File(directory, name)
            file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var remaining = length
                val deadline = System.nanoTime() + 120_000_000_000L
                while (remaining > 0) {
                    if (!current.active) throw IOException("Transfer stopped")
                    if (System.nanoTime() > deadline) throw TransferFailure(408, "Upload timed out")
                    val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    if (count < 0) throw EOFException("Incomplete upload")
                    output.write(buffer, 0, count)
                    remaining -= count
                }
            }
            if (!current.active) throw IOException("Transfer stopped")
            try { if (skillUpload) skillReceiver(file) else importer(file) }
            catch (failure: Exception) {
                throw TransferFailure(422, "${if (skillUpload) "Skill staging" else "Book import"} failed: ${failure.message ?: "invalid file"}")
            }
            notify(Event(name = name, imported = !skillUpload, skillPending = skillUpload))
            val result = if (skillUpload) "Pending phone confirmation: $name" else "Imported: $name"
            respond(socket, if (skillUpload) 202 else 201, "text/plain; charset=utf-8", result.toByteArray(Charsets.UTF_8))
        } finally { directory.deleteRecursively() }
    }

    private fun notify(event: Event) { runCatching { onEvent(event) } }

    private fun respond(socket: Socket, status: Int, type: String, bytes: ByteArray) {
        val label = when (status) { 200 -> "OK"; 201 -> "Created"; 202 -> "Accepted"; else -> "Request rejected" }
        val header = "HTTP/1.1 $status $label\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Connection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n" +
            "Referrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'none'; script-src 'nonce-transfer'; " +
            "connect-src 'self'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'\r\n\r\n"
        socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
    }

    private fun page(token: String) = """<!doctype html><html lang="en"><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1"><title>Wireless book transfer</title>
        <style>body{font:18px system-ui;max-width:42em;margin:3em auto;padding:0 1em}button,input{font:inherit;margin:1em 0}li{margin:.6em 0}</style>
        <h1>Wireless book transfer</h1><p>Keep the reader app open on the same Wi-Fi network.</p>
        <p>EPUB, FB2, TXT, Markdown, PDF, MOBI, AZW3. Up to 20 files, 128 MiB per file, 512 MiB per session.</p>
        <label>Upload category <select id="category"><option value="upload">Books</option><option value="skills">Reading Skills — confirm on phone</option></select></label>
        <p>Reading Skills: .skill.md, .md or .json, up to 64 KiB each. Receiving a Skill does not install or execute it.</p>
        <input id="files" type="file" multiple accept=".epub,.fb2,.txt,.md,.markdown,.pdf,.mobi,.azw3">
        <br><button id="send">Import books</button><p id="status" role="status"></p><ul id="results"></ul>
        <script nonce="transfer">const button=document.getElementById('send'), picker=document.getElementById('files');
        const category=document.getElementById('category');
        category.onchange=()=>{picker.value='';picker.accept=category.value==='skills'?'.skill.md,.md,.markdown,.json':'.epub,.fb2,.txt,.md,.markdown,.pdf,.mobi,.azw3';button.textContent=category.value==='skills'?'Send Skills for review':'Import books';};
        button.onclick=async()=>{const skills=category.value==='skills', limit=skills?65536:134217728;const files=Array.from(picker.files), status=document.getElementById('status');
        if(!files.length||files.length>20||files.some(f=>!f.size||f.size>limit)||files.reduce((n,f)=>n+f.size,0)>536870912){status.textContent='Choose 1–20 nonempty files within the category size limits.';return;}
        button.disabled=true;picker.disabled=true;category.disabled=true;document.getElementById('results').textContent='';
        try{for(let i=0;i<files.length;i++){const file=files[i];status.textContent='Importing '+(i+1)+' / '+files.length;
        const item=document.createElement('li');document.getElementById('results').append(item);
        try{const response=await fetch('/$token/'+(skills?'skills':'upload')+'?name='+encodeURIComponent(file.name),{method:'POST',headers:{'Content-Type':'application/octet-stream','X-Transfer-Token':'$token'},body:file});
        item.textContent=file.name+': '+await response.text();}catch(e){item.textContent=file.name+': connection lost';}}
        status.textContent='Batch finished. Check each result below.';}finally{button.disabled=false;picker.disabled=false;category.disabled=false;}};</script></html>""".trimIndent()

    companion object {
        const val MAX_FILE_BYTES = 128L * 1024 * 1024
        const val MAX_SESSION_BYTES = 512L * 1024 * 1024
        const val MAX_FILES = 20

        internal fun forTests(cache: File, importer: (File) -> Unit,
            skillReceiver: (File) -> Unit = { throw IOException("Skill receiver unavailable") }) =
            WirelessTransferServer(cache, importer, {}, skillReceiver)

        private fun wifiAddresses(): List<InetAddress> = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual &&
                (it.name.startsWith("wlan") || it.name.startsWith("wifi") || it.name.startsWith("ap") ||
                    it.name.startsWith("swlan")) }
            .flatMap { it.inetAddresses.toList() }.filter { it is Inet4Address && it.isSiteLocalAddress }
    }
}

internal class TransferFailure(val status: Int, message: String) : IOException(message)

internal data class TransferRequest(val method: String, val target: String, val headers: Map<String, String>) {
    val contentLength: Long get() = headers["content-length"]?.takeIf { it.matches(Regex("[0-9]{1,12}")) }
        ?.toLongOrNull() ?: throw TransferFailure(411, "Content-Length is required")

    companion object {
        fun read(input: InputStream): TransferRequest {
            var total = 0
            val deadline = System.nanoTime() + 15_000_000_000L
            fun line(): String {
                val result = ByteArrayOutputStream()
                while (true) {
                    if (System.nanoTime() > deadline) throw TransferFailure(408, "Headers timed out")
                    val byte = input.read()
                    if (byte < 0) throw EOFException("Incomplete request")
                    total++
                    if (total > 16 * 1024 || result.size() >= 4096) throw TransferFailure(431, "Headers too large")
                    if (byte == 10) {
                        val bytes = result.toByteArray()
                        if (bytes.isEmpty() || bytes.last() != 13.toByte() || bytes.dropLast(1).contains(13.toByte())) throw TransferFailure(400, "Invalid header line")
                        return String(bytes, 0, bytes.size - 1, Charsets.US_ASCII)
                    }
                    if (byte != 13 && byte !in 32..126) throw TransferFailure(400, "Invalid header character")
                    result.write(byte)
                }
            }
            val parts = line().split(' ')
            if (parts.size != 3 || parts[2] != "HTTP/1.1" || parts[0] !in setOf("GET", "POST"))
                throw TransferFailure(400, "Invalid request line")
            val headers = linkedMapOf<String, String>()
            while (true) {
                val header = line()
                if (header.isEmpty()) break
                val name = header.substringBefore(':').lowercase(Locale.ROOT)
                if (':' !in header || !name.matches(Regex("[a-z0-9-]+")) || name in headers || headers.size >= 64)
                    throw TransferFailure(400, "Invalid or duplicate header")
                headers[name] = header.substringAfter(':').trim()
            }
            if ("transfer-encoding" in headers || "expect" in headers)
                throw TransferFailure(400, "Unsupported transfer encoding")
            return TransferRequest(parts[0], parts[1], headers)
        }

        fun fileName(encoded: String, skill: Boolean = false): String {
            val name = try { URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8") }
                catch (_: IllegalArgumentException) { throw TransferFailure(400, "Invalid filename encoding") }
            if (name.isBlank() || name.toByteArray(Charsets.UTF_8).size > 240 || name.startsWith('.') ||
                name.any { it == '/' || it == '\\' || it == ':' || it.code < 32 || it.code == 127 } ||
                '\uFFFD' in name || name.substringAfterLast('.', "").lowercase(Locale.ROOT) !in
                (if (skill) setOf("json", "md", "markdown") else setOf("epub", "fb2", "txt", "md", "markdown", "pdf", "mobi", "azw3")))
                throw TransferFailure(400, "Invalid filename or unsupported format")
            return name
        }
    }
}
