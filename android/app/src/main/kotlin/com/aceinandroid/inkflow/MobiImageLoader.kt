package com.aceinandroid.inkflow

import android.graphics.Bitmap
import java.io.File

object MobiImageLoader {
    fun load(file: File, href: String): Bitmap? = runCatching {
        MobiBook.imageFile(file, href)?.let { image -> decodeBookBitmap { image.inputStream() } }
    }.getOrNull()
}
