package com.aceinandroid.inkflow

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Base64
import com.caverock.androidsvg.SVG
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal fun decodeBookBitmap(open: () -> InputStream): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    open().use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        val svg = open().use(SVG::getFromInputStream)
        val viewBox = svg.documentViewBox
        val width = svg.documentWidth.takeIf { it > 0 } ?: viewBox?.width() ?: 1024f
        val height = svg.documentHeight.takeIf { it > 0 } ?: viewBox?.height() ?: 768f
        if (!width.isFinite() || !height.isFinite() || width <= 0 || height <= 0) return null
        val scale = minOf(1f, 2048f / width, 2048f / height,
            sqrt(4_000_000.0 / (width.toDouble() * height)).toFloat())
        val bitmapWidth = (width * scale).roundToInt().coerceAtLeast(1)
        val bitmapHeight = (height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawPicture(svg.renderToPicture(bitmapWidth, bitmapHeight))
        }
    }
    val options = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.RGB_565
        var sample = 1
        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048 ||
            bounds.outWidth.toLong() * bounds.outHeight / sample / sample > 4_000_000) sample *= 2
        inSampleSize = sample
    }
    return open().use { BitmapFactory.decodeStream(it, null, options) }
}

object EpubImageLoader {
    fun load(file: File, chapter: Chapter, href: String): Bitmap? = runCatching {
        if (href.startsWith("inline-svg:")) {
            val bytes = href.removePrefix("inline-svg:").toByteArray(Charsets.UTF_8)
            return@runCatching decodeBookBitmap { ByteArrayInputStream(bytes) }
        }
        if (href.startsWith("data:", ignoreCase = true)) {
            val comma = href.indexOf(',')
            if (comma < 0 || !href.substring(0, comma).startsWith("data:image/", ignoreCase = true) ||
                !href.substring(0, comma).endsWith(";base64", ignoreCase = true)) return@runCatching null
            val bytes = Base64.decode(href.substring(comma + 1), Base64.DEFAULT)
            return@runCatching decodeBookBitmap { ByteArrayInputStream(bytes) }
        }
        val path = BookParser.resourcePath(chapter.path, href)
        ZipFile(file).use { zip ->
            val entry = zip.getEntry(path) ?: return@use null
            decodeBookBitmap { zip.getInputStream(entry) }
        }
    }.getOrNull()
}
