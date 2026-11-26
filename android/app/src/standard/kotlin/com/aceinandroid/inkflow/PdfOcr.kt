package com.aceinandroid.inkflow

import java.io.File

/** The standard build keeps PDF text extraction; scanned-page OCR is in fullOcr. */
internal object PdfOcr {
    fun recognize(file: File, pageIndex: Int): String = ""
}
