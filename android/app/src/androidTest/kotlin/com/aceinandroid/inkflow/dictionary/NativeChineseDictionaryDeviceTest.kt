package com.aceinandroid.inkflow.dictionary

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aceinandroid.inkflow.BookStore
import com.aceinandroid.inkflow.BuildConfig
import com.aceinandroid.inkflow.ai.NativeOfflineDictionary
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NativeChineseDictionaryDeviceTest {
    private fun isolated(run: (Context) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(target.cacheDir, "chinese-device-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(target) {
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        }
        try { run(context) } finally { root.deleteRecursively() }
    }

    @Test fun fullOcrLooksUpPackagedChineseWithoutAnyImport() {
        assumeTrue(BuildConfig.FLAVOR == "fullOcr")
        isolated { context ->
            val imports = NativeDictionaryImports(context)
            assertTrue(imports.repository.list().isEmpty())
            val bundled = NativeBundledChineseDictionary(context)
            BookStore(context).use { store ->
                val dictionary = NativeOfflineDictionary(context, store)
                for (word in listOf("中国", "学习", "仈")) {
                    val hit = bundled.lookup(word)
                    assertNotNull("Packaged lookup missing $word", hit)
                    assertEquals(word, hit!!.headword)
                    assertEquals("Bundled Chinese", hit.sourceLabel)
                    assertTrue(hit.definitions.isNotEmpty())
                    assertEquals(hit, bundled.lookup(word))
                    assertTrue(dictionary.lookupOffline(word)!!.definitions.isNotEmpty())
                }
                assertNotNull(bundled.lookup("仈")?.phonetic)
            }
            assertTrue("Lookup must not import the corpus", imports.repository.list().isEmpty())
            assertFalse(File(context.filesDir, "dictionaries/imported").exists())
            assertTrue(context.cacheDir.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun fullOcrExplicitBundledImportPersistsDefinitionsAndPronunciation() {
        assumeTrue(BuildConfig.FLAVOR == "fullOcr")
        isolated { context ->
            val packaged = context.assets.list("dictionaries/chinese").orEmpty()
                .filter { it.endsWith(".json") || it.endsWith(".json.gz") }
            assertTrue("No packaged Chinese shards", packaged.isNotEmpty())
            val imported = NativeDictionaryImports(context).importBundledChinese()
            assertTrue("Expected the full corpus", imported.entryCount > 100000)
            val repository = NativeDictionaryImports(context).repository
            assertEquals(1, repository.list().size)
            assertTrue(repository.lookup("中国")!!.definitions.isNotEmpty())
            assertTrue(repository.lookup("学习")!!.definitions.isNotEmpty())
            assertEquals("bā", repository.lookup("仈")?.phonetic)
            assertTrue(context.cacheDir.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun standardWithoutPackagedAssetsReturnsMissAndActionableImportError() {
        assumeTrue(BuildConfig.FLAVOR == "standard")
        isolated { context ->
            assertNull(NativeBundledChineseDictionary(context).lookup("中国"))
            val imports = NativeDictionaryImports(context)
            try {
                imports.importBundledChinese()
                fail("Standard must not silently report a bundled import")
            } catch (error: IllegalStateException) {
                assertTrue(error.message.orEmpty().contains("not packaged"))
                assertTrue(error.message.orEmpty().contains("JSON"))
            }
            assertTrue(imports.repository.list().isEmpty())
            assertFalse(File(context.filesDir, "dictionaries/imported").exists())
            assertTrue(context.cacheDir.listFiles().orEmpty().isEmpty())
        }
    }
}
