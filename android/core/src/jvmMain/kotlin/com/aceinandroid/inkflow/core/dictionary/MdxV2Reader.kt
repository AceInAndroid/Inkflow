package com.aceinandroid.inkflow.core.dictionary

import com.aceinandroid.inkflow.core.OfflineLookupEntry
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.zip.Adler32
import java.util.zip.Inflater

/**
 * Bounded MDX 2.x importer: unencrypted, type 0 (raw) / type 2 (zlib) blocks only.
 * UTF-8, UTF-16LE and JVM-provided GB18030 (including GBK/GB2312 aliases) are accepted.
 * HTML becomes inert plain text; MDD assets and stylesheet rendering are not imported.
 *
 * Format research (independent implementation, no decoder code copied):
 * https://github.com/liuyug/mdict-utils/blob/master/mdict_utils/base/readmdict.py
 * https://github.com/mumu-lhl/dict_reader/blob/master/lib/src/dict_reader_base.dart
 */
internal object MdxV2Reader {
    private const val MAX_BLOCK = 32 * 1024 * 1024
    private const val MAX_INDEX = 16 * 1024 * 1024
    private const val MAX_TOTAL = 256L * 1024 * 1024
    private const val MAX_ENTRIES = 500000
    private const val MAX_BLOCKS = 100000
    private const val MAX_RECORD = 64 * 1024

    private data class Header(val charset: Charset, val width: Int)
    private data class Key(val word: String, val offset: Long)
    private data class Block(val packed: Int, val expanded: Int, val count: Int = 0,
                             val first: String = "", val last: String = "")

    /** The caller owns input; scratch belongs to the unpublished import directory. */
    fun read(input: InputStream, scratch: File, source: String, consume: (OfflineLookupEntry) -> Unit) {
        val stream = DataInputStream(object : FilterInputStream(input) {
            private var total = 0L
            private fun count(size: Int): Int {
                if (size > 0) {
                    total += size
                    require(total <= MAX_TOTAL) { "MDX file exceeds the 256 MiB import limit" }
                }
                return size
            }
            override fun read(): Int = `in`.read().also { if (it >= 0) count(1) }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = count(`in`.read(bytes, offset, length))
        })
        var expandedBytes = 0L
        fun unpack(block: Block): ByteArray {
            expandedBytes += block.expanded
            require(expandedBytes <= MAX_TOTAL) { "Expanded MDX exceeds the 256 MiB import limit" }
            return decodeBlock(bytes(stream, block.packed), block.expanded)
        }
        val header = header(stream)
        val keyHeader = bytes(stream, 40)
        require(checksum(keyHeader) == unsignedInt(stream)) { "MDX key header checksum mismatch" }
        val numbers = DataInputStream(keyHeader.inputStream())
        val keyBlockCount = number(numbers, MAX_BLOCKS.toLong(), "key block count").toInt()
        val entryCount = number(numbers, MAX_ENTRIES.toLong(), "entry count").toInt()
        require(keyBlockCount in 1..entryCount) { "Invalid MDX key block count" }
        val indexExpanded = number(numbers, MAX_INDEX.toLong(), "key index expanded size").toInt()
        val indexPacked = number(numbers, MAX_INDEX.toLong() + 8, "key index compressed size").toInt()
        val keysPacked = number(numbers, MAX_TOTAL, "key section size")
        require(indexExpanded > 0 && indexPacked >= 8) { "Invalid MDX key index size" }
        val index = DataInputStream(unpack(Block(indexPacked, indexExpanded)).inputStream())
        val keyBlocks = List(keyBlockCount) {
            val count = number(index, entryCount.toLong(), "key block entries").toInt()
            require(count > 0) { "Empty MDX key block" }
            val first = indexWord(index, header)
            val last = indexWord(index, header)
            block(index).copy(count = count, first = first, last = last)
        }
        require(index.available() == 0) { "Trailing MDX key index data" }
        require(keyBlocks.sumOf { it.count.toLong() } == entryCount.toLong()) { "MDX key entry count mismatch" }
        require(keyBlocks.sumOf { it.packed.toLong() } == keysPacked) { "MDX key section size mismatch" }
        val keys = ArrayList<Key>(entryCount)
        val byWord = HashMap<String, Int>()
        for (block in keyBlocks) {
            val data = DataInputStream(unpack(block).inputStream())
            val start = keys.size
            repeat(block.count) {
                val offset = number(data, MAX_TOTAL, "record offset")
                val word = terminatedWord(data, header)
                require(word.isNotBlank() && word.length <= 256) { "Invalid MDX headword" }
                require(keys.isEmpty() || keys.last().offset <= offset) { "MDX record offsets are not ordered" }
                require(byWord.put(DictionaryEntries.key(word), keys.size) == null) { "Duplicate MDX headword: $word" }
                keys += Key(word, offset)
            }
            require(data.available() == 0) { "MDX key block entry count mismatch" }
            require(keys[start].word == block.first && keys.last().word == block.last) { "MDX key block boundaries mismatch" }
        }
        require(keys.first().offset == 0L) { "MDX first record offset must be zero" }
        val recordBlockCount = number(stream, MAX_BLOCKS.toLong(), "record block count").toInt()
        require(recordBlockCount > 0) { "MDX has no record blocks" }
        require(number(stream, MAX_ENTRIES.toLong(), "record entry count") == entryCount.toLong()) { "MDX record count mismatch" }
        val recordIndexSize = number(stream, MAX_INDEX.toLong(), "record index size")
        require(recordIndexSize == recordBlockCount * 16L) { "MDX record index size mismatch" }
        val recordsPacked = number(stream, MAX_TOTAL, "record section size")
        val recordBlocks = List(recordBlockCount) { block(stream) }
        require(recordBlocks.sumOf { it.packed.toLong() } == recordsPacked) { "MDX record section size mismatch" }
        val recordSize = recordBlocks.sumOf { it.expanded.toLong() }
        require(recordSize in 1..MAX_TOTAL && keys.last().offset < recordSize) { "MDX record offset is outside record data" }
        // Validate every range before any per-record allocation or seek. Equal offsets are aliases.
        val ends = LongArray(keys.size)
        var nextOffset = recordSize
        for (i in keys.indices.reversed()) {
            if (i < keys.lastIndex && keys[i].offset != keys[i + 1].offset) nextOffset = keys[i + 1].offset
            ends[i] = nextOffset
            require(ends[i] - keys[i].offset in 1..MAX_RECORD.toLong()) { "MDX record exceeds the 64 KiB entry limit" }
        }
        val recordsFile = File(scratch, "mdx-records.tmp")
        try {
            recordsFile.outputStream().buffered().use { out -> recordBlocks.forEach { out.write(unpack(it)) } }
            require(stream.read() == -1) { "Trailing MDX file data is unsupported" }
            RandomAccessFile(recordsFile, "r").use { records ->
                fun record(index: Int): String {
                    val key = keys[index]
                    records.seek(key.offset)
                    val raw = ByteArray((ends[index] - key.offset).toInt())
                    records.readFully(raw)
                    return decode(raw, header.charset).trimEnd('\u0000').trim().also {
                        require('\u0000' !in it) { "Embedded NUL in MDX definition" }
                    }
                }
                val definitions = LinkedHashMap<Int, String>()
                for (i in keys.indices) {
                    var current = i
                    val visited = hashSetOf<Int>()
                    val plain: String
                    while (true) {
                        require(visited.add(current) && visited.size <= 64) { "MDX redirect cycle or depth limit" }
                        val cached = definitions[current]
                        if (cached != null) { plain = cached; break }
                        val definition = record(current)
                        if (definition.startsWith("@@@LINK=")) {
                            val target = definition.removePrefix("@@@LINK=").trim()
                            current = byWord[DictionaryEntries.key(target)] ?: error("Missing MDX redirect target: $target")
                            continue
                        }
                        val document = Jsoup.parseBodyFragment(definition)
                        document.select("script,style,iframe,object,embed,link,img,audio,video,source").remove()
                        plain = document.body().text().replace(Regex("`[0-9]{1,3}`"), " ").trim()
                        definitions[current] = plain
                        if (definitions.size > 128) definitions.remove(definitions.keys.first())
                        break
                    }
                    consume(DictionaryEntries.decode(keys[i].word, plain, source))
                }
            }
        } finally { recordsFile.delete() }
    }

    private fun header(input: DataInputStream): Header {
        val length = unsignedInt(input)
        require(length in 2..(128 * 1024).toLong() && length % 2 == 0L) { "Invalid MDX UTF-16LE header size" }
        val raw = bytes(input, length.toInt())
        val littleEndianChecksum = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
        require(checksum(raw) == littleEndianChecksum) { "MDX header checksum mismatch" }
        val text = decode(raw, Charsets.UTF_16LE).removePrefix("\uFEFF").trimEnd('\u0000').trim()
        require(!text.contains("<!DOCTYPE", ignoreCase = true) && !text.contains("<!ENTITY", ignoreCase = true)) {
            "MDX XML declarations with entities are unsupported"
        }
        validateHeaderAttributes(text)
        val parser = Parser.xmlParser().setTrackErrors(16)
        val document = Jsoup.parse(text, "", parser)
        require(parser.errors.isEmpty() && document.children().size == 1) { "Invalid MDX dictionary header" }
        val dictionary = document.children().single()
        require(dictionary.tagName() == "Dictionary") { "MDX dictionary header is missing" }
        val version = dictionary.attr("GeneratedByEngineVersion")
        require(Regex("2\\.[0-9]+").matches(version)) { "Only MDX engine version 2.x is supported" }
        val encryption = dictionary.attr("Encrypted")
        require(encryption.isEmpty() || encryption == "0" || encryption.equals("No", ignoreCase = true)) {
            "Encrypted MDX dictionaries are unsupported"
        }
        val encoding = dictionary.attr("Encoding").ifEmpty { "UTF-8" }.uppercase(Locale.ROOT).replace('_', '-')
        val name = when (encoding) {
            "UTF-8", "UTF8" -> "UTF-8"
            "UTF-16", "UTF-16LE", "UTF16" -> "UTF-16LE"
            "GBK", "GB2312", "GB18030" -> "GB18030"
            else -> error("Unsupported MDX encoding: $encoding")
        }
        require(Charset.isSupported(name)) { "MDX encoding is unavailable on this device: $name" }
        return Header(Charset.forName(name), if (name == "UTF-16LE") 2 else 1)
    }

    /** Jsoup tolerates duplicate attributes and uses case-insensitive lookup; flags must be unambiguous. */
    private fun validateHeaderAttributes(text: String) {
        val opening = Regex("^\\s*(?:<\\?xml\\s[^?]*\\?>\\s*)?<Dictionary(?=\\s|/|>)").find(text)
            ?: error("Unsupported MDX dictionary header structure")
        var offset = opening.range.last + 1
        val names = hashSetOf<String>()
        while (true) {
            while (offset < text.length && text[offset].isWhitespace()) offset++
            require(offset < text.length) { "Unterminated MDX header" }
            if (text[offset] == '>' || text.startsWith("/>", offset)) return
            val start = offset
            while (offset < text.length && (text[offset].isLetterOrDigit() || text[offset] in "_:.-")) offset++
            require(offset > start) { "Invalid MDX header attribute" }
            require(names.add(text.substring(start, offset).lowercase(Locale.ROOT))) { "Duplicate MDX header attribute" }
            while (offset < text.length && text[offset].isWhitespace()) offset++
            require(offset < text.length && text[offset++] == '=') { "Invalid MDX header attribute assignment" }
            while (offset < text.length && text[offset].isWhitespace()) offset++
            require(offset < text.length && (text[offset] == '\'' || text[offset] == '"')) { "Unquoted MDX header attribute" }
            val quote = text[offset++]
            while (offset < text.length && text[offset] != quote) offset++
            require(offset < text.length) { "Unterminated MDX header attribute" }
            offset++
            require(offset < text.length && (text[offset].isWhitespace() || text[offset] == '>' || text.startsWith("/>", offset))) {
                "Missing MDX attribute separator"
            }
        }
    }

    private fun indexWord(input: DataInputStream, header: Header): String {
        val units = input.readUnsignedShort()
        require(units in 1..1024) { "Invalid MDX index headword length" }
        val word = decode(bytes(input, units * header.width), header.charset).trim()
        repeat(header.width) { require(input.readUnsignedByte() == 0) { "Missing MDX index terminator" } }
        return word
    }

    private fun terminatedWord(input: DataInputStream, header: Header): String {
        val word = ByteArrayOutputStream()
        while (true) {
            val first = input.readUnsignedByte()
            val second = if (header.width == 2) input.readUnsignedByte() else 0
            if (first == 0 && second == 0) break
            word.write(first)
            if (header.width == 2) word.write(second)
            require(word.size() <= 1024) { "MDX headword is too long" }
        }
        return decode(word.toByteArray(), header.charset).trim()
    }

    private fun block(input: DataInputStream): Block {
        val packed = number(input, MAX_BLOCK.toLong() + 8, "compressed block size").toInt()
        val expanded = number(input, MAX_BLOCK.toLong(), "expanded block size").toInt()
        require(packed >= 8 && expanded > 0) { "Invalid MDX block size" }
        return Block(packed, expanded)
    }

    /** Exact marker, size, checksum, and consumption: concatenated streams cannot bypass limits. */
    private fun decodeBlock(raw: ByteArray, expected: Int): ByteArray {
        val marker = (raw[0].toInt() and 255) or ((raw[1].toInt() and 255) shl 8) or
            ((raw[2].toInt() and 255) shl 16) or ((raw[3].toInt() and 255) shl 24)
        require(marker != 1) { "MDX LZO compression is unsupported" }
        require(marker == 0 || marker == 2) { "Unsupported MDX compression or encryption flags" }
        val output = if (marker == 0) {
            require(raw.size - 8 == expected) { "MDX uncompressed block size mismatch" }
            raw.copyOfRange(8, raw.size)
        } else {
            val inflater = Inflater()
            try {
                inflater.setInput(raw, 8, raw.size - 8)
                val result = ByteArray(expected)
                var offset = 0
                while (offset < expected) {
                    val count = inflater.inflate(result, offset, expected - offset)
                    offset += count
                    if (inflater.finished()) break
                    require(count > 0) { "Truncated MDX zlib block or preset dictionary is unsupported" }
                }
                val extra = inflater.inflate(ByteArray(1))
                require(offset == expected && extra == 0 && inflater.finished() && inflater.remaining == 0) {
                    "MDX zlib size mismatch, trailing data, or concatenated streams"
                }
                result
            } finally { inflater.end() }
        }
        val expectedChecksum = unsignedInt(DataInputStream(ByteArrayInputStream(raw, 4, 4)))
        require(checksum(output) == expectedChecksum) { "MDX block checksum mismatch" }
        return output
    }

    private fun number(input: DataInputStream, max: Long, field: String): Long = input.readLong().also {
        require(it in 0..max) { "Invalid or oversized MDX $field" }
    }
    private fun unsignedInt(input: DataInputStream): Long = input.readInt().toLong() and 0xffffffffL
    private fun bytes(input: DataInputStream, size: Int): ByteArray = ByteArray(size).also(input::readFully)
    private fun checksum(bytes: ByteArray): Long = Adler32().apply { update(bytes) }.value
    private fun decode(bytes: ByteArray, charset: Charset): String = charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
}
