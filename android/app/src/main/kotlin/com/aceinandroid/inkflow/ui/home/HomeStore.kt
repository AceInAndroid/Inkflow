package com.aceinandroid.inkflow.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AtomicFile
import com.aceinandroid.inkflow.ReadingSummary
import com.aceinandroid.inkflow.StoredBook
import java.io.File

/** Presentation preferences belong to this device, never to book progress or sync. */
enum class HomeStyle { CLASSIC, IMMERSIVE, MINIMAL, EINK }

class HomeStore(context: Context) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("inkflow_home", Context.MODE_PRIVATE)
    private val wallpaper = AtomicFile(File(app.filesDir, "inkflow_home_wallpaper.jpg"))

    fun style(): HomeStyle = HomeStyle.entries.firstOrNull {
        it.name == preferences.getString("style", null)
    } ?: HomeStyle.CLASSIC
    fun setStyle(style: HomeStyle) { preferences.edit().putString("style", style.name).apply() }
    fun motto(): String = preferences.getString("motto", "").orEmpty()
    fun setMotto(text: String) { preferences.edit().putString("motto", text.trim().take(240)).apply() }

    fun loadWallpaper(): Bitmap? = try {
        wallpaper.openRead().use { BitmapFactory.decodeStream(it) }
    } catch (_: Exception) { null }

    /** Copy while the SAF grant is valid; validate and downsample before replacing the old image. */
    fun importWallpaper(uri: Uri) {
        val temporary = File.createTempFile("home-wallpaper-", ".image", app.cacheDir)
        try {
            app.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Unreadable image" }
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 20 * 1024 * 1024) { "Image exceeds 20 MB" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Invalid image" }
            val options = BitmapFactory.Options().apply {
                inSampleSize = 1
                while (bounds.outWidth / inSampleSize > 1600 || bounds.outHeight / inSampleSize > 1600) inSampleSize *= 2
            }
            val bitmap = requireNotNull(BitmapFactory.decodeFile(temporary.path, options)) { "Invalid image" }
            try {
                val output = wallpaper.startWrite()
                try {
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
                    wallpaper.finishWrite(output)
                } catch (failure: Exception) {
                    wallpaper.failWrite(output)
                    throw failure
                }
            } finally { bitmap.recycle() }
        } finally { temporary.delete() }
    }

    fun removeWallpaper() { wallpaper.delete() }
}

/** Metadata edits cannot turn an unread book into the continue-reading suggestion. */
internal fun recentHomeBooks(books: List<StoredBook>, summary: ReadingSummary): List<StoredBook> {
    val lastSessions = summary.history.groupBy { it.bookId }.mapValues { (_, records) -> records.maxOf { it.date } }
    return books.filter { it.id in lastSessions || it.position > 0 || it.progress > 0 || it.cfi.isNotBlank() }
        .sortedWith(compareByDescending<StoredBook> { it.updatedAt.ifBlank { lastSessions[it.id].orEmpty() } }
            .thenByDescending { it.id })
}
