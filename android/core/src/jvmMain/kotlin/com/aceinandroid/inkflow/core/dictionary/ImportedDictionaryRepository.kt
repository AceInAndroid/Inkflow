package com.aceinandroid.inkflow.core.dictionary

import com.aceinandroid.inkflow.core.OfflineLookupEntry
import java.io.*
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.Properties
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

data class ImportedDictionary(val id: String, val name: String, val entryCount: Int, val enabled: Boolean)

/** Immutable imports are published with a single directory rename after full validation. No network. */
class ImportedDictionaryRepository(private val root: File) {
    fun list(): List<ImportedDictionary> = synchronized(lock) { dictionaries() }

    fun setEnabled(id: String, enabled: Boolean) = synchronized(lock) {
        val folder = folder(id)
        require(File(folder, "metadata").isFile) { "Dictionary is unavailable" }
        val marker = File(folder, "disabled")
        if (enabled) require(!marker.exists() || marker.delete()) { "Cannot enable dictionary" }
        else marker.writeText("")
    }

    /** Caller owns the input stream. ZIP accepts Flutter's shard layout without extracting paths. */
    fun import(name: String, input: InputStream): ImportedDictionary = synchronized(lock) {
        val suffix = name.lowercase(Locale.ROOT)
        require(!suffix.endsWith(".mdd")) { "MDD resource archives are unsupported; choose an MDX dictionary" }
        require(suffix.endsWith(".json") || suffix.endsWith(".json.gz") || suffix.endsWith(".zip") || suffix.endsWith(".mdx")) {
            "Choose JSON, JSON.GZ, a ZIP of JSON shards, or unencrypted MDX v2 (zlib/uncompressed)"
        }
        require(root.isDirectory || root.mkdirs()) { "Cannot create dictionary storage" }
        val id = "dict-${UUID.randomUUID()}"
        val staging = File(root, ".import-${UUID.randomUUID()}")
        require(staging.mkdir()) { "Cannot create dictionary staging folder" }
        val writers = mutableMapOf<String, DataOutputStream>()
        val seen = hashSetOf<String>()
        var totalBytes = 0L
        var fileCount = 0
        var storedBytes = 0L
        val label = name.substringAfterLast('/').substringAfterLast('\\').take(120)
        fun save(entry: OfflineLookupEntry) {
            // Bound alias expansion as well as compressed/source bytes (modified UTF uses <=3 bytes per char).
            storedBytes += (entry.headword.length.toLong() + entry.phonetic.orEmpty().length +
                entry.partOfSpeech.orEmpty().length + entry.example.orEmpty().length +
                entry.definitions.sumOf { it.length.toLong() }) * 3 + 64 + entry.definitions.size * 2L
            require(storedBytes <= 256L * 1024 * 1024) { "Imported dictionary exceeds the 256 MiB storage budget" }
            val key = DictionaryEntries.key(entry.headword)
            require(seen.add(key)) { "Duplicate dictionary headword: ${entry.headword}" }
            require(seen.size <= 500000) { "Too many dictionary entries" }
            val bucket = bucket(key)
            val output = writers.getOrPut(bucket) {
                DataOutputStream(File(staging, "$bucket.bin").outputStream().buffered())
            }
            writeEntry(output, entry)
        }
        fun decode(stream: InputStream) {
            require(++fileCount <= 512) { "Too many dictionary shards" }
            val limited = object : FilterInputStream(stream) {
                private var bytes = 0L
                private fun checked(size: Int): Int {
                    if (size > 0) {
                        bytes += size; totalBytes += size
                        require(bytes <= 32L * 1024 * 1024 && totalBytes <= 256L * 1024 * 1024) {
                            "Decompressed dictionary exceeds import limit"
                        }
                    }
                    return size
                }
                override fun read(): Int = super.read().also { if (it >= 0) checked(1) }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    checked(`in`.read(buffer, offset, length))
            }
            val utf8 = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            DictionaryJson(InputStreamReader(limited, utf8).buffered()).entries { word, raw ->
                val entry = DictionaryEntries.decode(word, raw, label)
                save(entry)
            }
        }
        try {
            when {
                suffix.endsWith(".mdx") -> MdxV2Reader.read(input, staging, label, ::save)
                suffix.endsWith(".zip") -> {
                    // Do not close the caller's stream, including when GZIP closes a nested stream.
                    val zip = ZipInputStream(nonClosing(input))
                    zip.use {
                        var members = 0
                        while (true) {
                            val item = zip.nextEntry ?: break
                            require(++members <= 1024) { "Too many ZIP members" }
                            val path = item.name.replace('\\', '/')
                            require(!path.startsWith('/') && path.split('/').none { it == ".." } && ':' !in path) {
                                "Unsafe dictionary archive path"
                            }
                            if (!item.isDirectory) {
                                val entryName = path.lowercase(Locale.ROOT)
                                require(entryName.endsWith(".json") || entryName.endsWith(".json.gz")) {
                                    "ZIP must contain only JSON dictionary shards"
                                }
                                if (entryName.endsWith(".gz")) GZIPInputStream(nonClosing(zip)).use(::decode)
                                else decode(zip)
                            }
                            zip.closeEntry()
                        }
                    }
                }
                suffix.endsWith(".gz") -> GZIPInputStream(nonClosing(input)).use(::decode)
                else -> decode(input)
            }
            require(seen.isNotEmpty()) { "Dictionary has no entries" }
            writers.values.forEach { it.close() }; writers.clear()
            Properties().apply {
                setProperty("name", label)
                setProperty("entries", seen.size.toString())
            }.also { props -> File(staging, "metadata").outputStream().use { props.store(it, null) } }
            require(staging.renameTo(File(root, id))) { "Cannot publish dictionary import" }
            ImportedDictionary(id, label, seen.size, true)
        } finally {
            writers.values.forEach { runCatching { it.close() } }
            staging.deleteRecursively()
        }
    }

    fun lookup(selection: String): OfflineLookupEntry? = synchronized(lock) {
        val dictionaries = dictionaries().filter { it.enabled }
        for (candidate in DictionaryEntries.candidates(selection)) {
            val key = DictionaryEntries.key(candidate)
            for (dictionary in dictionaries) {
                val file = File(folder(dictionary.id), "${bucket(key)}.bin")
                if (!file.isFile) continue
                DataInputStream(file.inputStream().buffered()).use { stream ->
                    while (stream.available() > 0) {
                        val entry = readEntry(stream, dictionary.name)
                        if (DictionaryEntries.key(entry.headword) == key) return entry
                    }
                }
            }
        }
        null
    }

    private fun dictionaries(): List<ImportedDictionary> = root.listFiles().orEmpty()
        .filter { it.isDirectory && it.name.startsWith("dict-") }
        .sortedBy { it.name }
        .mapNotNull { directory -> runCatching {
            val properties = Properties().apply { File(directory, "metadata").inputStream().use(::load) }
            ImportedDictionary(directory.name, properties.getProperty("name"),
                properties.getProperty("entries").toInt(), !File(directory, "disabled").exists())
        }.getOrNull() }

    private fun folder(id: String): File {
        require(Regex("dict-[a-f0-9-]{36}").matches(id)) { "Invalid dictionary identity" }
        return File(root, id)
    }

    companion object {
        private val lock = Any()
        private fun bucket(key: String): String = ((key.first().code ushr 4) and 255).toString(16)
        private fun nonClosing(stream: InputStream) = object : FilterInputStream(stream) {
            override fun close() = Unit
        }
        private fun writeEntry(output: DataOutputStream, entry: OfflineLookupEntry) {
            output.writeUTF(entry.headword)
            output.writeUTF(entry.phonetic.orEmpty()); output.writeUTF(entry.partOfSpeech.orEmpty())
            output.writeUTF(entry.example.orEmpty()); output.writeInt(entry.definitions.size)
            entry.definitions.forEach(output::writeUTF)
        }
        private fun readEntry(input: DataInputStream, source: String): OfflineLookupEntry {
            val word = input.readUTF()
            val phonetic = input.readUTF().ifEmpty { null }
            val part = input.readUTF().ifEmpty { null }
            val example = input.readUTF().ifEmpty { null }
            val count = input.readInt()
            require(count in 1..128) { "Corrupt dictionary entry" }
            return OfflineLookupEntry(word, phonetic, part, List(count) { input.readUTF() }, example, source)
        }
    }
}
