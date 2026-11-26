package com.aceinandroid.inkflow

import android.graphics.Bitmap
import android.util.Base64
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.io.File

object Fb2ImageLoader {
    fun load(file: File, href: String): Bitmap? = runCatching {
        if (!href.startsWith('#') || href.length == 1) return@runCatching null
        val document = Jsoup.parse(file, null, "", Parser.xmlParser())
        val binary = document.select("FictionBook > binary")
            .firstOrNull { it.attr("id") == href.substring(1) } ?: return@runCatching null
        val bytes = Base64.decode(binary.text(), Base64.DEFAULT)
        decodeBookBitmap { ByteArrayInputStream(bytes) }
    }.getOrNull()
}
