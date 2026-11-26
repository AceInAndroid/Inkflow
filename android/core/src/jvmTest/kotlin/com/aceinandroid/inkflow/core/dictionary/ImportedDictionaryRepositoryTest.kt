package com.aceinandroid.inkflow.core.dictionary

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Test
import kotlin.test.*

class ImportedDictionaryRepositoryTest {
    private val chinese = """{"中国":{"w":"中国","p":"zhōng guó","s":[{"d":"国家","e":"中国文化"},{"d":"中华"}]},"中":{"w":"中","s":[{"d":"中间"}]}}"""

    private fun withRepository(block: (ImportedDictionaryRepository, java.io.File) -> Unit) {
        val directory = Files.createTempDirectory("dictionary-test").toFile()
        try { block(ImportedDictionaryRepository(directory), directory) }
        finally { directory.deleteRecursively() }
    }

    private fun gzip(text: String) = ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { it.write(text.toByteArray()) }
    }.toByteArray()

    private fun zip(vararg files: Pair<String, ByteArray>) = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> files.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
    }.toByteArray()

    @Test fun flutterGzipRoundTripPreservesAllSensesAndNeverShortensSelection() = withRepository { repo, root ->
        val imported = repo.import("4e.json.gz", ByteArrayInputStream(gzip(chinese)))
        assertEquals(2, imported.entryCount)
        val lookup = ImportedDictionaryRepository(root).lookup("中国")!!
        assertEquals("中国", lookup.headword)
        assertEquals("zhōng guó", lookup.phonetic)
        assertEquals(listOf("国家", "中华"), lookup.definitions)
        assertEquals("中国文化", lookup.example)
        assertNull(repo.lookup("中国文化"))
        repo.setEnabled(imported.id, false)
        assertNull(repo.lookup("中国"))
        repo.setEnabled(imported.id, true)
        assertNotNull(repo.lookup("中国"))
    }

    @Test fun importedEnglishSupportsMorphologyAndLongFieldNames() = withRepository { repo, _ ->
        repo.import("english.json", """{"run":{"phonetic":"rʌn","definitions":["move quickly"],"example":"I run."}}""".byteInputStream())
        assertEquals("run", repo.lookup("running")?.headword)
        assertEquals("I run.", repo.lookup("running")?.example)
    }

    @Test fun zipSupportsNestedFlutterGzipShards() = withRepository { repo, _ ->
        repo.import("Chinese.zip", ByteArrayInputStream(zip("assets/dictionaries/chinese/4e.json.gz" to gzip(chinese))))
        assertNotNull(repo.lookup("中国"))
    }

    @Test fun failedImportsNeverPublishOrDamageExistingDictionary() = withRepository { repo, root ->
        repo.import("good.json", chinese.byteInputStream())
        val invalid = listOf(
            """{"word":"fine","broken":{}}""",
            """{"word":"fine","word":"duplicate"}""",
            """{"word":"fine"} trailing""",
            """{"中国":{"w":"中","s":[{"d":"wrong headword"}]}}""",
            """{"bad":"\q"}""",
            "{}",
        )
        invalid.forEach { json -> assertFails { repo.import("bad.json", json.byteInputStream()) } }
        assertEquals(1, repo.list().size)
        assertNotNull(repo.lookup("中国"))
        assertFalse(root.listFiles().orEmpty().any { it.name.startsWith(".import-") })
    }

    @Test fun corruptGzipArchiveTraversalAndInvalidMdxAreRejected() = withRepository { repo, _ ->
        assertFails { repo.import("bad.json.gz", ByteArrayInputStream(byteArrayOf(1, 2, 3))) }
        assertFails { repo.import("bad.zip", ByteArrayInputStream(zip("../4e.json" to chinese.toByteArray()))) }
        val error = assertFails { repo.import("dictionary.mdx", chinese.byteInputStream()) }
        assertTrue(error is java.io.EOFException || error.message.orEmpty().contains("MDX"))
        assertTrue(repo.list().isEmpty())
    }

    @Test fun invalidUtf8AndOversizedEntryAreRejected() = withRepository { repo, _ ->
        assertFails { repo.import("bad.json", ByteArrayInputStream(byteArrayOf(123, 34, -1, 34, 58, 34, 97, 34, 125))) }
        assertFails { repo.import("big.json", ("{\"word\":\"" + "x".repeat(16001) + "\"}").byteInputStream()) }
    }

    @Test fun parserHandlesEscapesAndRejectsExcessiveNesting() = withRepository { repo, _ ->
        repo.import("escaped.json", """{"word":"line\n\u4e2d\"quote\""}""".byteInputStream())
        assertEquals("line\n中\"quote\"", repo.lookup("word")?.definitions?.single())
        assertFails { repo.import("deep.json", ("{\"x\":" + "[".repeat(20) + "\"x\"" + "]".repeat(20) + "}").byteInputStream()) }
    }
    @Test fun realFlutterCorpusImportsAndRemainsQueryableOffline() = withRepository { repo, _ ->
        val source = generateSequence(java.io.File(System.getProperty("user.dir"))) { it.parentFile }
            .map { java.io.File(it, "assets/dictionaries/chinese") }.first { it.isDirectory }
        val shards = source.listFiles()!!.filter { it.name.endsWith(".json.gz") }
        assertEquals(82, shards.size)
        val archive = zip(*shards.map { it.name to it.readBytes() }.toTypedArray())
        val imported = repo.import("Flutter Chinese.zip", ByteArrayInputStream(archive))
        assertEquals(293150, imported.entryCount)
        assertNotNull(repo.lookup("中国"))
        assertNotNull(repo.lookup("中"))
        assertNull(repo.lookup("中国不是一个词条的整段选区"))
    }

}
