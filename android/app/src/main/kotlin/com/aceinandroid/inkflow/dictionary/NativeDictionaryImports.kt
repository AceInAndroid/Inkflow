package com.aceinandroid.inkflow.dictionary

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.aceinandroid.inkflow.core.dictionary.ImportedDictionary
import com.aceinandroid.inkflow.core.dictionary.ImportedDictionaryRepository
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NativeDictionaryImports(private val context: Context) {
    val repository = ImportedDictionaryRepository(File(context.filesDir, "dictionaries/imported"))

    fun importDocument(uri: Uri): ImportedDictionary {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?: error("Cannot read dictionary file name")
        return requireNotNull(context.contentResolver.openInputStream(uri)) { "Cannot open dictionary" }
            .use { repository.import(name, it) }
    }

    private fun isChineseShard(name: String): Boolean = name.endsWith(".json") || name.endsWith(".json.gz")

    /** Explicit installation accepts both repository JSON shards and their compressed equivalents. */
    fun importBundledChinese(): ImportedDictionary {
        val root = listOf("dictionaries/chinese", "flutter_assets/assets/dictionaries/chinese")
            .firstOrNull { path -> context.assets.list(path).orEmpty().any(::isChineseShard) }
            ?: error("Chinese assets are not packaged in this build; import JSON, JSON.GZ, or a ZIP of the Flutter shards")
        val archive = File.createTempFile("chinese-import-", ".zip", context.cacheDir)
        try {
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                context.assets.list(root).orEmpty().filter(::isChineseShard).sorted().distinctBy { it.removeSuffix(".gz") }.forEach { name ->
                    zip.putNextEntry(ZipEntry(name))
                    context.assets.open("$root/$name").use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            return archive.inputStream().use { repository.import("Chinese dictionary.zip", it) }
        } finally { archive.delete() }
    }
}
