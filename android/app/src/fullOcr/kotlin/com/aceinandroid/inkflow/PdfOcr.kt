package com.aceinandroid.inkflow

import android.util.LruCache
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.io.File

internal object PdfOcr {
    private data class PageKey(val path: String, val size: Long, val modified: Long, val index: Int)

    private val cache = LruCache<PageKey, String>(256)
    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    fun recognize(file: File, pageIndex: Int): String {
        val key = PageKey(file.absolutePath, file.length(), file.lastModified(), pageIndex)
        synchronized(cache) { cache.get(key) }?.let { return it }
        val bitmap = renderPdfPage(file, pageIndex)
        val text = try {
            Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0))).text.trim()
        } finally {
            bitmap.recycle()
        }
        synchronized(cache) { cache.put(key, text) }
        return text
    }
}
