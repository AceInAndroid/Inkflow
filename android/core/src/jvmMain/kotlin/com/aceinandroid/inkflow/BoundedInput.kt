package com.aceinandroid.inkflow

import java.io.ByteArrayOutputStream
import java.io.InputStream

fun InputStream.readBounded(maxBytes: Int): ByteArray? {
    require(maxBytes in 0 until Int.MAX_VALUE)
    val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
    val buffer = ByteArray(8192)
    while (output.size() <= maxBytes) {
        val count = read(buffer, 0, minOf(buffer.size, maxBytes + 1 - output.size()))
        if (count < 0) break
        output.write(buffer, 0, count)
    }
    return output.toByteArray().takeIf { it.size <= maxBytes }
}
