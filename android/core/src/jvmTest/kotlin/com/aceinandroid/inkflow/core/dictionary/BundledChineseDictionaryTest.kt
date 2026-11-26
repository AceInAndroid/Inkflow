package com.aceinandroid.inkflow.core.dictionary

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import org.junit.Test
import kotlin.test.*

class BundledChineseDictionaryTest {
    private val json = """{"中国":{"w":"中国","p":"zhōng guó","s":[{"d":"国家","e":"中国文化"},{"d":"中华"}]}}"""

    @Test fun plainShardIsLazyExactAndCachesDefinitionsAndMisses() {
        val opened = mutableListOf<String>()
        val dictionary = BundledChineseDictionary { name ->
            opened += name
            if (name == "4e.json") json.byteInputStream() else null
        }
        assertTrue(opened.isEmpty())
        assertNull(dictionary.lookup("running"))
        assertTrue(opened.isEmpty())
        val entry = assertNotNull(dictionary.lookup(" 中国 "))
        assertEquals(listOf("国家", "中华"), entry.definitions)
        assertEquals("zhōng guó", entry.phonetic)
        assertEquals("中国文化", entry.example)
        assertEquals("Bundled Chinese", entry.sourceLabel)
        assertEquals(entry, dictionary.lookup("中国"))
        assertEquals(listOf("4e.json"), opened)
        assertNull(dictionary.lookup("中国文化"))
        assertNull(dictionary.lookup("中国文化"))
        assertEquals(2, opened.size)
    }

    @Test fun gzipShardsUseTheSamePrefixAndPreserveDefinitions() {
        val opened = mutableListOf<String>()
        val compressed = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(json.toByteArray()) }
        }.toByteArray()
        val dictionary = BundledChineseDictionary { name ->
            opened += name
            if (name == "4e.json.gz") compressed.inputStream() else null
        }
        assertEquals(listOf("国家", "中华"), dictionary.lookup("中国")?.definitions)
        assertEquals(listOf("4e.json", "4e.json.gz"), opened)
    }

    @Test fun missingAssetsAreGracefulAndMissCacheIsBounded() {
        var opened = 0
        val dictionary = BundledChineseDictionary { opened++; null }
        assertNull(dictionary.lookup("中国"))
        assertNull(dictionary.lookup("中国"))
        assertEquals(2, opened)
        repeat(64) { dictionary.lookup("中$it") }
        assertEquals(130, opened)
        assertNull(dictionary.lookup("中国"))
        assertEquals(132, opened)
    }

    @Test fun definitionCacheAlsoEnforcesItsCharacterBudget() {
        val shard = (0..59).joinToString(prefix = "{", postfix = "}") { "\"中$it\":\"${"x".repeat(5000)}\"" }
        var opened = 0
        val dictionary = BundledChineseDictionary { opened++; shard.byteInputStream() }
        repeat(60) { assertNotNull(dictionary.lookup("中$it")) }
        assertEquals(60, opened)
        assertNotNull(dictionary.lookup("中59"))
        assertEquals(60, opened)
        assertNotNull(dictionary.lookup("中0"))
        assertEquals(61, opened)
    }

    @Test fun malformedOrDuplicateShardDoesNotCachePartialResult() {
        for (broken in listOf(json.dropLast(1), json + "garbage", """{"中国":"one","中国":"two"}""")) {
            var opened = 0
            val dictionary = BundledChineseDictionary { opened++; broken.byteInputStream() }
            repeat(2) { assertFails { dictionary.lookup("中国") } }
            assertEquals(2, opened)
        }
    }

    @Test fun supplementaryCodePointsChooseUnicodePrefixRatherThanSurrogate() {
        val word = String(Character.toChars(0x20000))
        val names = mutableListOf<String>()
        val dictionary = BundledChineseDictionary { name ->
            names += name
            """{"$word":"supplementary definition"}""".byteInputStream()
        }
        assertEquals(listOf("supplementary definition"), dictionary.lookup(word)?.definitions)
        assertEquals(listOf("200.json"), names)
    }

    @Test fun actualRepositoryShardsAreUsableWithoutImport() {
        val root = sequenceOf(File("assets/dictionaries/chinese"), File("../../assets/dictionaries/chinese"))
            .firstOrNull(File::isDirectory) ?: error("Repository Chinese corpus unavailable")
        var reads = 0
        val dictionary = BundledChineseDictionary { name -> File(root, name).takeIf(File::isFile)?.let {
            reads++; it.inputStream()
        } }
        for (word in listOf("中国", "学习", "仈")) {
            val entry = assertNotNull(dictionary.lookup(word), word)
            assertEquals(word, entry.headword)
            assertTrue(entry.definitions.all { it.isNotBlank() })
            assertEquals(entry, dictionary.lookup(word))
        }
        assertEquals(3, reads)
    }
}
