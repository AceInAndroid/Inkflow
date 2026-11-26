package com.aceinandroid.inkflow.core.dictionary

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.file.Files
import java.util.zip.Adler32
import java.util.zip.DeflaterOutputStream
import org.junit.Test
import kotlin.test.*

class MdxV2ReaderTest {
    private data class Fixture(val bytes: ByteArray, val keyHeader: Int, val keyIndex: Int,
                               val keyBlock: Int, val recordHeader: Int, val recordIndex: Int, val recordBlock: Int)

    /** Independent minimal writer: v2 BE 64-bit indexes, LE header checksum, framed block checksums. */
    private fun fixture(
        entries: List<Pair<String, String>> = listOf("alpha" to "<b>first</b>", "beta" to "second", "中国" to "国家"),
        encoding: String = "UTF-8",
        encryption: String = "No",
        version: String = "2.0",
        keyCompression: Int = 2,
        keyIndexCompression: Int = 2,
        recordCompression: Int = 2,
        offsets: List<Long>? = null,
        mutateRecord: (ByteArray) -> ByteArray = { it },
        recordExpandedDelta: Int = 0,
        splitRecords: Boolean = false,
        sharedFirstRecord: Boolean = false,
    ): Fixture {
        val charset = Charset.forName(if (encoding == "UTF-16") "UTF-16LE" else if (encoding == "GBK") "GB18030" else encoding)
        val width = if (encoding.startsWith("UTF-16")) 2 else 1
        val records = ByteArrayOutputStream()
        val starts = entries.mapIndexed { index, (_, definition) ->
            records.size().toLong().also {
                if (!sharedFirstRecord || index != 0) records.write((definition + "\u0000").toByteArray(charset))
            }
        }
        val keys = entries.mapIndexed { i, entry -> (offsets ?: starts)[i] to entry.first }
        val groups = keys.chunked(2)
        val keyData = groups.map { group ->
            frame(binary { group.forEach { (offset, word) ->
                writeLong(offset); write(word.toByteArray(charset)); repeat(width) { writeByte(0) }
            } }, keyCompression)
        }
        fun DataOutputStream.boundary(word: String) {
            val data = word.toByteArray(charset)
            writeShort(data.size / width); write(data); repeat(width) { writeByte(0) }
        }
        val keyIndexData = binary { groups.forEachIndexed { i, group ->
            writeLong(group.size.toLong()); boundary(group.first().second); boundary(group.last().second)
            writeLong(keyData[i].size.toLong())
            writeLong(group.sumOf { 8 + it.second.toByteArray(charset).size + width }.toLong())
        } }
        val keyIndex = frame(keyIndexData, keyIndexCompression)
        val keyIndexExpanded = keyIndexData.size
        val recordData = records.toByteArray()
        val chunks = if (splitRecords) listOf(recordData.copyOfRange(0, 3), recordData.copyOfRange(3, recordData.size))
            else listOf(recordData)
        val recordBlocks = chunks.map { mutateRecord(frame(it, recordCompression)) }
        val header = ("<Dictionary GeneratedByEngineVersion=\"$version\" RequiredEngineVersion=\"2.0\" " +
            "Encrypted=\"$encryption\" Encoding=\"$encoding\" Title=\"Test dictionary\"/>\u0000").toByteArray(Charsets.UTF_16LE)
        val output = ByteArrayOutputStream()
        val data = DataOutputStream(output)
        data.writeInt(header.size); data.write(header); data.writeInt(Integer.reverseBytes(checksum(header).toInt()))
        val keyHeaderOffset = output.size()
        val numbers = binary {
            writeLong(groups.size.toLong()); writeLong(entries.size.toLong()); writeLong(keyIndexExpanded.toLong())
            writeLong(keyIndex.size.toLong()); writeLong(keyData.sumOf { it.size }.toLong())
        }
        data.write(numbers); data.writeInt(checksum(numbers).toInt())
        val keyIndexOffset = output.size(); data.write(keyIndex)
        val keyBlockOffset = output.size(); keyData.forEach(data::write)
        val recordHeaderOffset = output.size()
        data.writeLong(recordBlocks.size.toLong()); data.writeLong(entries.size.toLong())
        data.writeLong(recordBlocks.size * 16L); data.writeLong(recordBlocks.sumOf { it.size }.toLong())
        val recordIndexOffset = output.size()
        recordBlocks.forEachIndexed { i, block ->
            data.writeLong(block.size.toLong()); data.writeLong((chunks[i].size + recordExpandedDelta).toLong())
        }
        val recordBlockOffset = output.size(); recordBlocks.forEach(data::write)
        return Fixture(output.toByteArray(), keyHeaderOffset, keyIndexOffset, keyBlockOffset,
            recordHeaderOffset, recordIndexOffset, recordBlockOffset)
    }

    @Test fun rawAndZlibBlocksImportRealDefinitionsAndPersistOffline() = withRepository { repo, root ->
        for (compression in listOf(0, 2)) {
            val imported = repo.import("fixture-$compression.mdx", fixture(keyCompression = compression,
                keyIndexCompression = compression, recordCompression = compression).bytes.inputStream())
            assertEquals(3, imported.entryCount)
            assertEquals(listOf("first"), ImportedDictionaryRepository(root).lookup("alpha")?.definitions)
            assertEquals(listOf("国家"), repo.lookup("中国")?.definitions)
            assertNull(repo.lookup("中国整段选区"))
            repo.setEnabled(imported.id, false)
        }
        assertNull(repo.lookup("alpha"))
    }

    @Test fun utf16AndGb18030DefinitionsCanSpanRecordBlocks() = withRepository { repo, _ ->
        for (encoding in listOf("UTF-8", "UTF-16", "GBK")) {
            val imported = repo.import("$encoding.mdx", fixture(encoding = encoding, splitRecords = true).bytes.inputStream())
            assertEquals("国家", repo.lookup("中国")?.definitions?.single())
            repo.setEnabled(imported.id, false)
        }
    }

    @Test fun htmlResourcesAreInertAndLocalRedirectsResolve() = withRepository { repo, _ ->
        val entries = listOf("alpha" to "@@@LINK=beta\r\n", "beta" to
            "<style>hidden css</style><script>evil()</script><b>visible &amp; clear</b>" +
                "<img src='https://example.invalid/pixel'><audio src='sound://voice.mp3'></audio>")
        repo.import("html.mdx", fixture(entries = entries).bytes.inputStream())
        assertEquals(listOf("visible & clear"), repo.lookup("alpha")?.definitions)
        assertEquals(repo.lookup("alpha")?.definitions, repo.lookup("beta")?.definitions)
    }

    @Test fun sharedRecordOffsetsAreSupported() = withRepository { repo, _ ->
        repo.import("shared.mdx", fixture(entries = listOf("alpha" to "shared", "beta" to "shared"),
            sharedFirstRecord = true).bytes.inputStream())
        assertEquals(listOf("shared"), repo.lookup("alpha")?.definitions)
        assertEquals(repo.lookup("alpha")?.definitions, repo.lookup("beta")?.definitions)
    }

    @Test fun encryptionLzoUnknownVersionAndMddAreExplicitlyRejected() = withRepository { repo, _ ->
        for (encrypted in listOf("Yes", "1", "2", "3")) {
            rejected(repo, fixture(encryption = encrypted).bytes, "Encrypted")
        }
        for (version in listOf("1.2", "3.0")) rejected(repo, fixture(version = version).bytes, "version")
        rejected(repo, fixture(keyCompression = 1).bytes, "LZO")
        rejected(repo, fixture(recordCompression = 1).bytes, "LZO")
        rejected(repo, fixture(recordCompression = 3).bytes, "flags")
        val indexLzo = fixture().let { it.bytes.clone().apply { this[it.keyIndex] = 1 } }
        rejected(repo, indexLzo, "LZO")
        rejected(repo, fixture(encoding = "ISO-8859-1").bytes, "encoding")
        for (attribute in listOf("Encrypted", "encrypted")) {
            val duplicateHeader = fixture(encryption = "No\" $attribute=\"2").bytes
            rejected(repo, duplicateHeader, "Duplicate")
        }
        val error = assertFails { repo.import("resource.mdd", byteArrayOf().inputStream()) }
        assertTrue(error.message.orEmpty().contains("MDD"))
    }

    @Test fun corruptChecksumsAndTruncationAreRejectedWithoutPublishing() = withRepository { repo, _ ->
        val valid = fixture()
        for (offset in listOf(4, valid.keyHeader, valid.keyIndex + 4, valid.keyBlock + 4, valid.recordBlock + 4)) {
            val broken = valid.bytes.clone().also { it[offset] = (it[offset].toInt() xor 1).toByte() }
            assertFails { repo.import("bad.mdx", broken.inputStream()) }
        }
        for (length in listOf(0, 3, valid.keyHeader - 1, valid.keyBlock + 2, valid.bytes.size - 1)) {
            assertFails { repo.import("truncated.mdx", valid.bytes.copyOf(length).inputStream()) }
        }
        assertTrue(repo.list().isEmpty())
    }

    @Test fun hugeSizesUnsignedOverflowAndOffsetsAreRejectedBeforeAllocation() = withRepository { repo, _ ->
        val valid = fixture()
        val hugeHeader = valid.bytes.clone().also { ByteBuffer.wrap(it).putInt(0, Int.MAX_VALUE) }
        rejected(repo, hugeHeader, "header size")
        for (relative in listOf(0, 8, 16, 24, 32)) {
            val broken = valid.bytes.clone()
            ByteBuffer.wrap(broken).putLong(valid.keyHeader + relative, Long.MAX_VALUE)
            val numbers = broken.copyOfRange(valid.keyHeader, valid.keyHeader + 40)
            ByteBuffer.wrap(broken).putInt(valid.keyHeader + 40, checksum(numbers).toInt())
            assertFails { repo.import("huge.mdx", broken.inputStream()) }
        }
        for (value in listOf(Long.MAX_VALUE, -1L, 32L * 1024 * 1024 + 1)) {
            val broken = valid.bytes.clone()
            ByteBuffer.wrap(broken).putLong(valid.recordIndex + 8, value)
            assertFails { repo.import("huge.mdx", broken.inputStream()) }
        }
        for (offsets in listOf(listOf(0L, 1000000000L, 1000000001L), listOf(0L, 10L, 1L), listOf(1L, 5L, 10L))) {
            assertFails { repo.import("offset.mdx", fixture(offsets = offsets).bytes.inputStream()) }
        }
        rejected(repo, fixture(entries = listOf("big" to "x".repeat(65536))).bytes, "64 KiB")
    }

    @Test fun concatenatedZlibStreamsAndInflatedSizeLiesAreRejected() = withRepository { repo, _ ->
        val duplicate = fixture(mutateRecord = { raw -> raw + raw.copyOfRange(8, raw.size) })
        rejected(repo, duplicate.bytes, "concatenated")
        for (delta in listOf(-1, 1)) assertFails {
            repo.import("size.mdx", fixture(recordExpandedDelta = delta).bytes.inputStream())
        }
        val flags = fixture(mutateRecord = { raw -> raw.clone().also { it[1] = 1 } })
        rejected(repo, flags.bytes, "flags")
        val trailing = fixture().bytes + byteArrayOf(0)
        rejected(repo, trailing, "Trailing")
    }

    @Test fun duplicateHeadwordsRedirectCyclesAndMissingTargetsAreRejected() = withRepository { repo, root ->
        rejected(repo, fixture(entries = listOf("Word" to "one", "word" to "two")).bytes, "Duplicate")
        rejected(repo, fixture(entries = listOf("alpha" to "@@@LINK=beta", "beta" to "@@@LINK=alpha")).bytes, "cycle")
        rejected(repo, fixture(entries = listOf("alpha" to "@@@LINK=missing")).bytes, "Missing")
        assertTrue(repo.list().isEmpty())
        assertFalse(root.listFiles().orEmpty().any { it.name.startsWith(".import-") })
    }

    @Test fun recordCountsAndIndexLengthsMustAgree() = withRepository { repo, _ ->
        val valid = fixture()
        for ((offset, value) in listOf(valid.recordHeader + 8 to 4L, valid.recordHeader + 16 to 15L,
            valid.recordHeader + 24 to 1L)) {
            val broken = valid.bytes.clone()
            ByteBuffer.wrap(broken).putLong(offset, value)
            assertFails { repo.import("index.mdx", broken.inputStream()) }
        }
    }

    @Test fun failedMdxLeavesExistingImportsAndNoScratchFiles() = withRepository { repo, root ->
        repo.import("existing.json", """{"word":"existing definition"}""".byteInputStream())
        assertFails { repo.import("broken.mdx", fixture(mutateRecord = { it + byteArrayOf(0) }).bytes.inputStream()) }
        assertEquals(listOf("existing definition"), repo.lookup("word")?.definitions)
        assertEquals(1, repo.list().size)
        assertFalse(root.listFiles().orEmpty().any { it.name.startsWith(".import-") })
        assertFalse(root.walkTopDown().any { it.name == "mdx-records.tmp" })
    }

    @Test fun malformedEncodedRecordTextIsRejectedWithoutReplacement() = withRepository { repo, _ ->
        val corrupt = fixture(recordCompression = 0, mutateRecord = { raw ->
            raw.clone().also {
                it[8] = 0xff.toByte()
                ByteBuffer.wrap(it).putInt(4, checksum(it.copyOfRange(8, it.size)).toInt())
            }
        })
        assertFails { repo.import("malformed.mdx", corrupt.bytes.inputStream()) }
        assertTrue(repo.list().isEmpty())
    }

    private fun rejected(repo: ImportedDictionaryRepository, bytes: ByteArray, message: String) {
        val error = assertFails { repo.import("fixture.mdx", bytes.inputStream()) }
        assertTrue(error.message.orEmpty().contains(message), "Expected '$message', got ${error.message}")
    }
    private fun withRepository(block: (ImportedDictionaryRepository, File) -> Unit) {
        val root = Files.createTempDirectory("mdx-v2-test-").toFile()
        try { block(ImportedDictionaryRepository(root), root) } finally { root.deleteRecursively() }
    }
    private fun checksum(bytes: ByteArray) = Adler32().apply { update(bytes) }.value
    private fun binary(write: DataOutputStream.() -> Unit) = ByteArrayOutputStream().also { out ->
        DataOutputStream(out).use { it.write() }
    }.toByteArray()
    private fun frame(bytes: ByteArray, compression: Int): ByteArray = binary {
        writeInt(Integer.reverseBytes(compression)); writeInt(checksum(bytes).toInt())
        if (compression == 0) write(bytes)
        else write(ByteArrayOutputStream().also { out -> DeflaterOutputStream(out).use { it.write(bytes) } }.toByteArray())
    }
}
