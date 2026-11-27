package com.aceinandroid.inkflow.transfer

import com.aceinandroid.inkflow.core.ai.ReadingSkill
import com.aceinandroid.inkflow.core.ai.ReadingSkillRegistry
import com.aceinandroid.inkflow.core.ai.SkillCodec
import com.aceinandroid.inkflow.core.ai.SkillImportPreview
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.nio.file.Files

class SkillTransferServerTest {
    private val skill = ReadingSkill("custom.wifi", "Wireless", "Summary", "Do not execute on receipt")
    private fun send(uri: URI, category: String, name: String, bytes: ByteArray,
        token: String = uri.path.drop(1), declared: Int = bytes.size): String = Socket(uri.host, uri.port).use {
        it.soTimeout = 5000
        it.getOutputStream().apply {
            write(("POST ${uri.path}/$category?name=$name HTTP/1.1\r\nHost: ${uri.rawAuthority}\r\n" +
                "Content-Type: application/octet-stream\r\nX-Transfer-Token: $token\r\n" +
                "Content-Length: $declared\r\n\r\n").toByteArray())
            write(bytes); flush()
        }
        it.shutdownOutput()
        it.getInputStream().readBytes().toString(Charsets.UTF_8)
    }

    @Test fun skillUploadStagesForPhoneConfirmationAndMarkdownBookStillImportsNormally() {
        val directory = Files.createTempDirectory("skill-server").toFile()
        var books = 0
        var applications = 0
        val inbox = SkillTransferInbox(directory.resolve("inbox"),
            { SkillImportPreview.create(SkillCodec.decode(it), ReadingSkillRegistry()) },
            { _, _ -> applications++ })
        try {
            WirelessTransferServer.forTests(directory.resolve("cache"), { books++ },
                { inbox.receive(it.name, it.readBytes()); Unit }).use { server ->
                val uri = URI(server.startOn(InetAddress.getLoopbackAddress()))
                val response = send(uri, "skills", "wireless.skill.md", SkillCodec.encodeMarkdown(skill).toByteArray())
                assertTrue(response.startsWith("HTTP/1.1 202"))
                assertTrue(response.contains("Pending phone confirmation"))
                assertEquals(0, books)
                assertEquals(0, applications)
                assertEquals(skill, inbox.pending().single().preview.skills.single())
                assertTrue(send(uri, "upload", "book.md", "# Book".toByteArray()).startsWith("HTTP/1.1 201"))
                assertEquals(1, books)
                assertEquals(0, applications)
                inbox.confirm(inbox.pending().single())
                assertEquals(1, applications)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun malformedOversizedUnauthorizedAndBookFilesCannotEnterSkillInbox() {
        val directory = Files.createTempDirectory("skill-server").toFile()
        val inbox = SkillTransferInbox(directory.resolve("inbox"),
            { SkillImportPreview.create(SkillCodec.decode(it), ReadingSkillRegistry()) },
            { _, _ -> fail("Receive path must never apply") })
        try {
            WirelessTransferServer.forTests(directory.resolve("cache"), { fail("Must not import book") },
                { inbox.receive(it.name, it.readBytes()); Unit }).use { server ->
                val uri = URI(server.startOn(InetAddress.getLoopbackAddress()))
                val bytes = SkillCodec.encodeJson(skill).toByteArray()
                assertTrue(send(uri, "skills", "x.json", bytes, token = "wrong").startsWith("HTTP/1.1 403"))
                assertTrue(send(uri, "skills", "x.epub", bytes).startsWith("HTTP/1.1 400"))
                assertTrue(send(uri, "skills", "x.json", "{}".toByteArray()).startsWith("HTTP/1.1 422"))
                assertTrue(send(uri, "skills", "x.json", byteArrayOf(), declared = SkillCodec.MAX_FILE_BYTES + 1)
                    .startsWith("HTTP/1.1 413"))
                assertTrue(send(uri, "skills", "x.json", byteArrayOf(1), declared = 20).startsWith("HTTP/1.1 400"))
                assertTrue(inbox.pending().isEmpty())
                assertTrue(send(uri, "skills", "x.json", bytes).startsWith("HTTP/1.1 202"))
                assertEquals(1, inbox.pending().size)
            }
        } finally { directory.deleteRecursively() }
    }
}
