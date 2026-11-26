package com.aceinandroid.inkflow

import android.content.ContentResolver
import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

data class StoredBook(
    val id: Long,
    val title: String,
    val author: String,
    val file: File,
    val format: String,
    val position: Int,
    val cfi: String,
    val cover: File? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
    val progress: Double = 0.0,
    val groupId: Long = 0,
)

data class BookGroup(val id: Long, val name: String, val parentId: Long)

data class StoredBookmark(
    val id: Long,
    val content: String,
    val cfi: String,
    val chapter: String,
    val percentage: Double,
)

data class DeviceReadingPosition(
    val deviceId: String,
    val cfi: String,
    val progress: Double,
    val chapterTitle: String?,
    val updatedAt: Long,
)

data class StoredAnnotation(
    val id: Long,
    val content: String,
    val cfi: String,
    val chapter: String,
    val type: String,
    val color: String,
    val readerNote: String,
)

data class LibraryNote(
    val id: Long,
    val book: StoredBook,
    val content: String,
    val cfi: String,
    val chapter: String,
    val type: String,
    val readerNote: String,
    val workspaceId: String? = null,
    val title: String = "",
    val status: String = "active",
    val favorite: Boolean = false,
    val tags: List<String> = emptyList(),
    val updatedAt: Long = 0L,
    val sourceTypes: Set<String> = emptySet(),
) {
    val identity: String get() = workspaceId ?: "annotation-$id"
}

data class ReadingDay(val date: String, val seconds: Int)
data class ReadingBook(val id: Long, val title: String?, val seconds: Int)
data class ReadingRecord(val bookId: Long, val date: String, val seconds: Int)
data class ReadingSummary(
    val totalSeconds: Int,
    val daysRead: Int,
    val booksRead: Int,
    val recentDays: List<ReadingDay>,
    val books: List<ReadingBook>,
    val history: List<ReadingRecord> = emptyList(),
)

class BookStore(context: Context) : SQLiteOpenHelper(context, "app_database.db", null, 22) {
    private val appContext = context.applicationContext
    private val positions = appContext.getSharedPreferences("native_reader_positions", Context.MODE_PRIVATE)
    private val textPositions = appContext.getSharedPreferences("native_reader_text_positions", Context.MODE_PRIVATE)

    override fun onCreate(db: SQLiteDatabase) {
        val schema = appContext.assets.open("schema_v22.json").bufferedReader().use { JSONArray(it.readText()) }
        for (index in 0 until schema.length()) db.execSQL(schema.getString(index))
        db.execSQL("INSERT INTO tb_groups (id, name, parent_id, create_time, update_time) VALUES (0, 'Root', NULL, datetime('now'), datetime('now'))")
        db.execSQL("INSERT INTO tb_themes (background_color, text_color, background_image_path) VALUES ('fffbfbf3', 'ff343434', '')")
        db.execSQL("INSERT INTO tb_themes (background_color, text_color, background_image_path) VALUES ('ff040404', 'fffeffeb', '')")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Unsupported native database version: $oldVersion")
    }

    fun all(): List<StoredBook> = readableDatabase.query(
        "tb_books", arrayOf("id", "title", "author", "file_path", "last_read_position", "cover_path",
            "create_time", "update_time", "reading_percentage", "group_id"),
        "is_deleted = 0", null, null, null, "id DESC"
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val path = cursor.getString(3) ?: continue
                val file = File(appContext.filesDir, path)
                val cover = coverFile(cursor.getString(5))?.takeIf(File::isFile)
                add(StoredBook(id, cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty(),
                    file, file.extension.lowercase(), positions.getInt(id.toString(), 0),
                    cursor.getString(4).orEmpty(), cover, cursor.getString(6).orEmpty(),
                    cursor.getString(7).orEmpty(), cursor.getDouble(8), cursor.getLong(9)))
            }
        }
    }

    fun groups(): List<BookGroup> = readableDatabase.query("tb_groups",
        arrayOf("id", "name", "parent_id"), "id != 0 AND is_deleted = 0",
        null, null, null, "name COLLATE NOCASE, id").use { rows ->
        buildList {
            while (rows.moveToNext()) add(BookGroup(rows.getLong(0), rows.getString(1).orEmpty(),
                if (rows.isNull(2)) 0 else rows.getLong(2)))
        }
    }

    fun createGroup(name: String, parentId: Long = 0): BookGroup {
        val cleanName = checkedGroupName(name)
        requireGroup(parentId)
        val now = nowIso()
        val id = writableDatabase.insertOrThrow("tb_groups", null, ContentValues().apply {
            put("name", cleanName)
            put("parent_id", parentId)
            put("is_deleted", 0)
            put("create_time", now)
            put("update_time", now)
        })
        return BookGroup(id, cleanName, parentId)
    }

    fun renameGroup(groupId: Long, name: String) {
        require(groupId > 0) { "Root group cannot be renamed" }
        requireGroup(groupId)
        val changed = writableDatabase.update("tb_groups", ContentValues().apply {
            put("name", checkedGroupName(name))
            put("update_time", nowIso())
        }, "id = ? AND is_deleted = 0", arrayOf(groupId.toString()))
        check(changed == 1) { "Group is unavailable" }
    }

    fun moveGroup(groupId: Long, parentId: Long) {
        require(groupId > 0 && parentId >= 0) { "Invalid group" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            fun parentOf(id: Long): Long = db.query("tb_groups", arrayOf("parent_id"),
                "id = ? AND is_deleted = 0", arrayOf(id.toString()), null, null, null).use { rows ->
                require(rows.moveToFirst()) { "Group is unavailable" }
                if (rows.isNull(0)) 0L else rows.getLong(0)
            }
            parentOf(groupId)
            val visited = mutableSetOf<Long>()
            var ancestor = parentId
            while (ancestor != 0L) {
                require(visited.add(ancestor) && ancestor != groupId) {
                    "Cannot move a folder into itself or its subfolder"
                }
                ancestor = parentOf(ancestor)
            }
            val changed = db.update("tb_groups", ContentValues().apply {
                put("parent_id", parentId)
                put("update_time", nowIso())
            }, "id = ? AND is_deleted = 0", arrayOf(groupId.toString()))
            check(changed == 1) { "Group is unavailable" }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun moveBookToGroup(bookId: Long, groupId: Long) {
        requireGroup(groupId)
        val changed = writableDatabase.update("tb_books", ContentValues().apply {
            put("group_id", groupId)
            put("update_time", nowIso())
        }, "id = ? AND is_deleted = 0", arrayOf(bookId.toString()))
        require(changed == 1) { "Book is unavailable" }
    }

    fun deleteGroup(groupId: Long) {
        require(groupId > 0) { "Root group cannot be deleted" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val parentId = db.query("tb_groups", arrayOf("parent_id"),
                "id = ? AND is_deleted = 0", arrayOf(groupId.toString()), null, null, null).use { rows ->
                require(rows.moveToFirst()) { "Group is unavailable" }
                if (rows.isNull(0)) 0L else rows.getLong(0)
            }
            val parent = if (parentId == groupId || parentId == 0L) 0L else {
                db.query("tb_groups", arrayOf("id"), "id = ? AND is_deleted = 0",
                    arrayOf(parentId.toString()), null, null, null).use { rows ->
                    if (rows.moveToFirst()) parentId else 0L
                }
            }
            val now = nowIso()
            db.update("tb_books", ContentValues().apply {
                put("group_id", parent)
                put("update_time", now)
            }, "group_id = ?", arrayOf(groupId.toString()))
            db.update("tb_groups", ContentValues().apply {
                put("parent_id", parent)
                put("update_time", now)
            }, "parent_id = ? AND id != ? AND is_deleted = 0",
                arrayOf(groupId.toString(), groupId.toString()))
            db.update("tb_groups", ContentValues().apply {
                put("is_deleted", 1)
                put("update_time", now)
            }, "id = ?", arrayOf(groupId.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun checkedGroupName(name: String): String = name.trim().also {
        require(it.isNotEmpty() && it.length <= 80 && '\u0000' !in it) { "Invalid group name" }
    }

    private fun requireGroup(groupId: Long) {
        require(groupId >= 0) { "Invalid group" }
        if (groupId == 0L) return
        val exists = readableDatabase.query("tb_groups", arrayOf("id"),
            "id = ? AND is_deleted = 0", arrayOf(groupId.toString()), null, null, null).use {
            it.moveToFirst()
        }
        require(exists) { "Group is unavailable" }
    }

    fun import(uri: Uri): StoredBook {
        val resolver = appContext.contentResolver
        val name = displayName(resolver, uri)
        val extension = name.substringAfterLast('.', "").lowercase()
        require(extension in setOf("epub", "fb2", "txt", "md", "markdown", "pdf", "mobi", "azw3")) {
            "Unsupported book format: $extension"
        }
        val directory = File(appContext.filesDir, "file").apply { mkdirs() }
        val key = UUID.randomUUID().toString()
        val relativePath = "file/$key.$extension"
        val target = File(appContext.filesDir, relativePath)
        val coverPath = "cover/$key.png"
        val cover = File(appContext.filesDir, coverPath)
        try {
            resolver.openInputStream(uri)?.use { input -> target.outputStream().use(input::copyTo) }
                ?: error("Cannot open selected file")
            val digest = MessageDigest.getInstance("MD5")
            target.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val existing = readableDatabase.query("tb_books", arrayOf("id"),
                "file_md5 = ? AND is_deleted = 0", arrayOf(hash), null, null, null).use {
                if (it.moveToFirst()) it.getLong(0) else null
            }
            if (existing != null) {
                target.delete()
                return all().first { it.id == existing }
            }
            val txtRule = if (extension == "txt") TxtChapterRuleStore(appContext).activeRule() else null
            val metadata = BookParser.inspect(target, name.substringBeforeLast('.'), txtRule?.expression())
            val savedCover = if (extension == "epub" && metadata.coverPath != null) {
                try {
                    val bitmap = java.util.zip.ZipFile(target).use { zip ->
                        val entry = zip.getEntry(metadata.coverPath)
                        if (entry == null) null else decodeBookBitmap { zip.getInputStream(entry) }
                    }
                    if (bitmap != null) {
                        try {
                            cover.parentFile!!.mkdirs()
                            cover.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                            coverPath
                        } finally { bitmap.recycle() }
                    } else null
                } catch (_: Exception) {
                    cover.delete()
                    null
                }
            } else null
            val now = nowIso()
            val values = ContentValues().apply {
                put("title", metadata.title)
                put("cover_path", savedCover.orEmpty())
                put("file_path", relativePath)
                put("last_read_position", "")
                put("reading_percentage", 0.0)
                put("author", metadata.author)
                put("is_deleted", 0)
                put("description", "")
                put("create_time", now)
                put("update_time", now)
                put("rating", 0.0)
                put("group_id", 0)
                put("file_md5", hash)
            }
            val id = writableDatabase.insertOrThrow("tb_books", null, values)
            if (txtRule != null) try {
                TxtChapterRuleStore(appContext).bindBook(id, txtRule)
            } catch (failure: Exception) {
                writableDatabase.delete("tb_books", "id = ?", arrayOf(id.toString()))
                throw failure
            }
            return StoredBook(id, metadata.title, metadata.author, target, extension, 0, "",
                if (savedCover != null) cover else null, now, now)
        } catch (error: Exception) {
            if (extension in setOf("mobi", "azw3")) MobiBook.deleteCache(target)
            cover.delete()
            target.delete()
            throw error
        }
    }

    private fun coverFile(path: String?): File? {
        if (path.isNullOrBlank()) return null
        val directory = File(appContext.filesDir, "cover").canonicalFile
        return File(appContext.filesDir, path).canonicalFile.takeIf { it.parentFile == directory }
    }

    /** Edit the existing v22 metadata row; cover files remain in the normal backup/sync directory. */
    fun saveBookMetadata(bookId: Long, title: String, author: String, coverUri: Uri? = null) {
        val cleanTitle = title.trim()
        val cleanAuthor = author.trim()
        require(cleanTitle.isNotEmpty() && cleanTitle.length <= 500 && cleanAuthor.length <= 500)
        val oldCover = readableDatabase.query("tb_books", arrayOf("cover_path"),
            "id = ? AND is_deleted = 0", arrayOf(bookId.toString()), null, null, null).use {
            check(it.moveToFirst()) { "Book no longer exists" }
            it.getString(0)
        }
        var savedCover: File? = null
        try {
            if (coverUri != null) {
                val temporary = File.createTempFile("cover-import-", ".image", appContext.cacheDir)
                try {
                    appContext.contentResolver.openInputStream(coverUri)?.use { input ->
                        temporary.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= 16L * 1024 * 1024) { "Cover exceeds 16 MB" }
                                output.write(buffer, 0, count)
                            }
                        }
                    } ?: error("Cannot open cover")
                    val bitmap = decodeBookBitmap { temporary.inputStream() } ?: error("Invalid cover image")
                    try {
                        val directory = File(appContext.filesDir, "cover")
                        check(directory.isDirectory || directory.mkdirs())
                        savedCover = File(directory, "${UUID.randomUUID()}.jpg")
                        savedCover!!.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
                    } finally { bitmap.recycle() }
                } finally { temporary.delete() }
            }
            val values = ContentValues().apply {
                put("title", cleanTitle)
                put("author", cleanAuthor)
                put("update_time", nowIso())
                savedCover?.let { put("cover_path", "cover/${it.name}") }
            }
            check(writableDatabase.update("tb_books", values, "id = ? AND is_deleted = 0",
                arrayOf(bookId.toString())) == 1) { "Book no longer exists" }
        } catch (failure: Exception) {
            savedCover?.delete()
            throw failure
        }
        if (savedCover != null) coverFile(oldCover)?.delete()
    }

    fun savePosition(bookId: Long, position: Int, cfi: String? = null, progress: Double? = null) {
        positions.edit().putInt(bookId.toString(), position).apply()
        if (cfi != null && progress != null) {
            val db = writableDatabase
            val deviceId = NativeProgressIdentity.getOrCreate(appContext)
            db.beginTransaction()
            try {
                val values = ContentValues().apply {
                    put("last_read_position", cfi)
                    put("reading_percentage", progress.coerceIn(0.0, 1.0))
                    put("update_time", nowIso())
                }
                check(db.update("tb_books", values, "id = ?", arrayOf(bookId.toString())) == 1) {
                    "Book no longer exists"
                }
                if (cfi.isNotBlank()) {
                    val previous = db.rawQuery(
                        "SELECT MAX(updated_at) FROM tb_book_device_positions WHERE device_id = ?",
                        arrayOf(deviceId)).use {
                        if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
                    }
                    db.insertWithOnConflict("tb_book_device_positions", null, ContentValues().apply {
                        put("book_id", bookId)
                        put("device_id", deviceId)
                        put("cfi", cfi)
                        put("progress", progress.coerceIn(0.0, 1.0))
                        put("updated_at", maxOf(System.currentTimeMillis(), previous + 1))
                    }, SQLiteDatabase.CONFLICT_REPLACE)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } else touchBook(bookId, progress)
    }

    fun farthestOtherPosition(bookId: Long): DeviceReadingPosition? {
        val ownId = NativeProgressIdentity.getOrCreate(appContext)
        return readableDatabase.rawQuery("""
            SELECT p.device_id, p.cfi, p.progress, p.chapter_title, p.updated_at
            FROM tb_book_device_positions p JOIN tb_books b ON b.id = p.book_id
            WHERE p.book_id = ? AND p.device_id != ? AND p.cfi != ''
              AND p.progress > COALESCE(b.reading_percentage, 0) + 0.01
            ORDER BY p.progress DESC, p.updated_at DESC LIMIT 1
        """.trimIndent(), arrayOf(bookId.toString(), ownId)).use {
            if (it.moveToFirst()) DeviceReadingPosition(it.getString(0), it.getString(1),
                it.getDouble(2), it.getString(3), it.getLong(4)) else null
        }
    }

    fun textOffset(bookId: Long, chapter: Int): Int =
        if (textPositions.getInt("$bookId:chapter", -1) == chapter)
            textPositions.getInt("$bookId:offset", 0) else 0

    fun saveTextPosition(bookId: Long, chapter: Int, offset: Int, progress: Double? = null) {
        positions.edit().putInt(bookId.toString(), chapter).apply()
        textPositions.edit().putInt("$bookId:chapter", chapter)
            .putInt("$bookId:offset", offset.coerceAtLeast(0)).apply()
        touchBook(bookId, progress)
    }

    private fun touchBook(bookId: Long, progress: Double? = null) {
        writableDatabase.update("tb_books", ContentValues().apply {
            put("update_time", nowIso())
            if (progress != null && progress.isFinite()) put("reading_percentage", progress.coerceIn(0.0, 1.0))
        },
            "id = ? AND is_deleted = 0", arrayOf(bookId.toString()))
    }

    private fun nowIso(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())

    fun removeBook(bookId: Long) {
        val paths = readableDatabase.query("tb_books", arrayOf("file_path", "cover_path"),
            "id = ? AND is_deleted = 0", arrayOf(bookId.toString()), null, null, null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) to cursor.getString(1) else null
        } ?: return
        val updated = writableDatabase.update("tb_books", ContentValues().apply {
            put("is_deleted", 1)
            put("update_time", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date()))
        }, "id = ? AND is_deleted = 0", arrayOf(bookId.toString()))
        if (updated == 0) return
        positions.edit().remove(bookId.toString()).apply()
        textPositions.edit().remove("$bookId:chapter").remove("$bookId:offset").apply()
        TxtChapterRuleStore(appContext).forgetBook(bookId)
        val file = File(appContext.filesDir, paths.first)
        if (file.canonicalFile.parentFile == File(appContext.filesDir, "file").canonicalFile) {
            if (file.extension.lowercase() in setOf("mobi", "azw3")) MobiBook.deleteCache(file)
            file.delete()
        }
        coverFile(paths.second)?.delete()
    }

    fun bookmarks(bookId: Long): List<StoredBookmark> = readableDatabase.query(
        "tb_notes", arrayOf("id", "content", "cfi", "chapter", "color"),
        "book_id = ? AND type = ?", arrayOf(bookId.toString(), "bookmark"),
        null, null, "CAST(color AS REAL), id"
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(StoredBookmark(
                cursor.getLong(0), cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty(),
                cursor.getString(3).orEmpty(), cursor.getString(4)?.toDoubleOrNull() ?: 0.0
            ))
        }
    }

    fun addBookmark(bookId: Long, cfi: String, chapter: String, content: String, percentage: Double) {
        val exists = readableDatabase.query("tb_notes", arrayOf("id"),
            "book_id = ? AND type = ? AND cfi = ?", arrayOf(bookId.toString(), "bookmark", cfi),
            null, null, null).use { it.moveToFirst() }
        if (exists) return
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val values = ContentValues().apply {
            put("book_id", bookId)
            put("content", content)
            put("cfi", cfi)
            put("chapter", chapter)
            put("type", "bookmark")
            put("color", percentage.toString())
            put("reader_note", "")
            put("create_time", now)
            put("update_time", now)
        }
        writableDatabase.insertOrThrow("tb_notes", null, values)
    }

    fun deleteBookmark(bookId: Long, bookmarkId: Long) {
        writableDatabase.delete("tb_notes", "id = ? AND book_id = ? AND type = ?",
            arrayOf(bookmarkId.toString(), bookId.toString(), "bookmark"))
    }

    fun annotations(bookId: Long): List<StoredAnnotation> = readableDatabase.query(
        "tb_notes", arrayOf("id", "content", "cfi", "chapter", "type", "color", "reader_note"),
        "book_id = ? AND type IN (?, ?)", arrayOf(bookId.toString(), "highlight", "underline"),
        null, null, "id DESC"
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(StoredAnnotation(
                cursor.getLong(0), cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty(),
                cursor.getString(3).orEmpty(), cursor.getString(4).orEmpty(),
                cursor.getString(5).orEmpty(), cursor.getString(6).orEmpty()
            ))
        }
    }

    fun libraryNotes(): List<LibraryNote> {
        val books = all().associateBy(StoredBook::id)
        if (books.isEmpty()) return emptyList()
        val workspace = ReadingNoteWorkspaceStore(this).snapshot(books)
        val legacy = readableDatabase.query("tb_notes",
            arrayOf("id", "book_id", "content", "cfi", "chapter", "type", "reader_note", "update_time"),
            "type IN (?, ?, ?)", arrayOf("highlight", "underline", "bookmark"),
            null, null, "update_time DESC, id DESC").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    if (cursor.getLong(0) in workspace.mappedAnnotationIds) continue
                    val book = books[cursor.getLong(1)] ?: continue
                    val cfi = cursor.getString(3).orEmpty()
                    if (book.format != "epub" || cfi.isBlank()) continue
                    add(LibraryNote(cursor.getLong(0), book, cursor.getString(2).orEmpty(), cfi,
                        cursor.getString(4).orEmpty(), cursor.getString(5).orEmpty(),
                        cursor.getString(6).orEmpty(), updatedAt = parseNoteTime(cursor.getString(7)),
                        sourceTypes = setOf("annotation")))
                }
            }
        }
        return (workspace.notes + legacy).sortedByDescending(LibraryNote::updatedAt)
    }

    private fun parseNoteTime(raw: String?): Long = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(raw.orEmpty().take(19))?.time ?: 0L
    }.getOrDefault(0L)

    fun addReadingTime(bookId: Long, day: String, seconds: Int) {
        if (seconds <= 0) return
        require(day.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = db.rawQuery(
                "SELECT id, reading_time FROM tb_reading_time WHERE book_id = ? AND DATE(date) = DATE(?) LIMIT 1",
                arrayOf(bookId.toString(), day)).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) to cursor.getInt(1) else null
            }
            if (existing == null) {
                db.insertOrThrow("tb_reading_time", null, ContentValues().apply {
                    put("book_id", bookId)
                    put("date", day)
                    put("reading_time", seconds)
                })
            } else {
                db.update("tb_reading_time", ContentValues().apply {
                    put("reading_time", existing.second + seconds)
                }, "id = ?", arrayOf(existing.first.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun readingSummary(): ReadingSummary {
        val days = mutableMapOf<String, Int>()
        val books = mutableMapOf<Long, Int>()
        val history = mutableListOf<ReadingRecord>()
        readableDatabase.query("tb_reading_time", arrayOf("book_id", "date", "reading_time"),
            "reading_time > 0", null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) {
                val seconds = cursor.getInt(2)
                val day = cursor.getString(1)?.take(10) ?: continue
                days[day] = (days[day] ?: 0) + seconds
                val bookId = cursor.getLong(0)
                books[bookId] = (books[bookId] ?: 0) + seconds
                history.add(ReadingRecord(bookId, day, seconds))
            }
        }
        val titles = mutableMapOf<Long, String>()
        readableDatabase.query("tb_books", arrayOf("id", "title"), null, null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) titles[cursor.getLong(0)] = cursor.getString(1).orEmpty()
        }
        val calendar = Calendar.getInstance()
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val recent = (0 until 7).map {
            val day = formatter.format(calendar.time)
            calendar.add(Calendar.DATE, -1)
            ReadingDay(day, days[day] ?: 0)
        }.reversed()
        return ReadingSummary(days.values.sum(), days.size, books.size, recent,
            books.map { (id, seconds) -> ReadingBook(id, titles[id], seconds) }
                .sortedByDescending(ReadingBook::seconds), history)
    }

    fun saveAnnotation(bookId: Long, content: String, cfi: String, chapter: String,
                       type: String, color: String, readerNote: String,
                       expected: StoredAnnotation? = null): AnnotationMutation {
        require(type == "highlight" || type == "underline")
        require(color.matches(Regex("[0-9A-Fa-f]{6}")))
        require(content.isNotBlank() && EpubCfi.localRange(cfi) != null)
        return NativeAnnotationActions(writableDatabase).save(bookId, content, cfi, chapter,
            type, color.uppercase(Locale.US), readerNote, expected)
    }

    fun deleteAnnotation(bookId: Long, expected: StoredAnnotation): String =
        NativeAnnotationActions(writableDatabase).delete(bookId, expected)

    fun undoAnnotation(actionId: String): AnnotationUndoResult =
        NativeAnnotationActions(writableDatabase).undo(actionId)

    private fun displayName(resolver: ContentResolver, uri: Uri): String {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return uri.lastPathSegment ?: "book"
    }
}
