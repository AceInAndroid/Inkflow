package com.aceinandroid.inkflow

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal data class WebDavLibraryStatus(val database: WebDavResource?, val newerVersion: Int?)

internal class NativeWebDavSync(
    context: Context,
    private val client: WebDavClient,
) {
    private val appContext = context.applicationContext
    private val databasePath = "inkflow/database22.db"

    fun status(): WebDavLibraryStatus = NativeSyncIntegration.manual { fetchStatus() }

    private fun fetchStatus(): WebDavLibraryStatus {
        val entries = client.resources("inkflow")
        val newer = entries.mapNotNull { resource ->
            Regex("database([0-9]+)\\.db").matchEntire(resource.name)
                ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 22 }
        }.maxOrNull()
        return WebDavLibraryStatus(entries.firstOrNull { it.name == "database22.db" && !it.directory }, newer)
    }

    fun upload(store: BookStore, expected: WebDavLibraryStatus) = NativeSyncIntegration.manual {
        rejectNewer(expected)
        val stage = temporaryDirectory()
        try {
            val archive = File(stage, "library.zip")
            NativeBackupManager(appContext).export(Uri.fromFile(archive), store)
            ZipFile(archive).use { zip ->
                val database = zip.getEntry("databases/app_database.db")
                    ?: throw IOException("Library database is missing")
                val dbFile = File(stage, "database22.db")
                zip.getInputStream(database).use { input -> dbFile.outputStream().use(input::copyTo) }
                val paths = databaseFiles(dbFile)
                client.ensureDirectory("inkflow/data/file")
                client.ensureDirectory("inkflow/data/cover")
                for (path in paths) {
                    val entry = zip.getEntry(path) ?: throw IOException("Library file is missing: $path")
                    val source = File(stage, "upload-${UUID.randomUUID()}")
                    try {
                        zip.getInputStream(entry).use { input -> source.outputStream().use(input::copyTo) }
                        client.upload("inkflow/data/$path", source)
                    } finally { source.delete() }
                }
                // A failed file upload must never publish a database that refers to absent files.
                requireUnchanged(expected)
                val condition = expected.database?.etag?.takeIf(::isStrongEtag)
                    ?.let { WebDavWriteCondition.Match(it) } ?: if (expected.database == null)
                    WebDavWriteCondition.Absent else null
                client.upload(databasePath, dbFile, condition)
            }
        } finally { stage.deleteRecursively() }
    }

    fun download(store: BookStore, expected: WebDavLibraryStatus) = NativeSyncIntegration.manual {
        rejectNewer(expected)
        if (expected.database == null) throw IOException("No remote library database")
        val stage = temporaryDirectory()
        try {
            val dbFile = File(stage, "database22.db")
            client.download(databasePath, dbFile, MAX_FILE_BYTES)
            val paths = databaseFiles(dbFile)
            var total = dbFile.length()
            val archive = File(stage, "library.zip")
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                zip.addFile("databases/app_database.db", dbFile)
                for (path in paths) {
                    val target = File(stage, "download-${UUID.randomUUID()}")
                    try {
                        client.download("inkflow/data/$path", target, MAX_FILE_BYTES)
                        total += target.length()
                        if (total > MAX_LIBRARY_BYTES) throw IOException("Remote library is too large")
                        zip.addFile(path, target)
                    } finally { target.delete() }
                }
            }
            requireUnchanged(expected)
            NativeBackupManager(appContext).restore(Uri.fromFile(archive), store,
                preserveLocalPreferences = true)
        } finally { stage.deleteRecursively() }
    }

    private fun databaseFiles(file: File): List<String> {
        if (file.length() < 1024 || file.length() > MAX_FILE_BYTES)
            throw IOException("Invalid remote library database")
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            if (db.version != 22) throw IOException("Unsupported library database version: ${db.version}")
            db.rawQuery("PRAGMA integrity_check", null).use { rows ->
                if (!rows.moveToFirst() || rows.getString(0) != "ok")
                    throw IOException("Remote library database is corrupt")
            }
            val paths = linkedSetOf<String>()
            db.rawQuery("SELECT file_path, cover_path FROM tb_books WHERE is_deleted = 0", null).use { rows ->
                while (rows.moveToNext()) {
                    paths += checkedPath(rows.getString(0), "file")
                    rows.getString(1)?.takeIf(String::isNotBlank)?.let { paths += checkedPath(it, "cover") }
                    if (paths.size > MAX_ENTRIES) throw IOException("Remote library has too many files")
                }
            }
            return paths.toList()
        } finally { db.close() }
    }

    private fun checkedPath(path: String?, directory: String): String {
        val parts = path?.split('/') ?: throw IOException("Missing library file path")
        if (parts.size != 2 || parts[0] != directory || parts[1].isBlank() ||
            parts[1] in setOf(".", "..") || '\\' in path || '\u0000' in path)
            throw IOException("Invalid library file path")
        return path
    }

    private fun requireUnchanged(expected: WebDavLibraryStatus) {
        val current = fetchStatus()
        rejectNewer(current)
        if (current.database != expected.database)
            throw IOException("Remote library changed during sync; review it before retrying")
    }

    private fun rejectNewer(status: WebDavLibraryStatus) {
        status.newerVersion?.let { throw IOException("Remote database version $it is newer than 22") }
    }

    private fun isStrongEtag(etag: String): Boolean =
        etag.length >= 2 && etag.first() == '"' && etag.last() == '"' &&
            etag.substring(1, etag.lastIndex).all { it.code in 0x21..0x7e && it != '"' }

    private fun temporaryDirectory(): File = File(appContext.cacheDir,
        "webdav-${UUID.randomUUID()}").also {
        if (!it.mkdir()) throw IOException("Cannot prepare WebDAV sync")
    }

    private fun ZipOutputStream.addFile(path: String, source: File) {
        putNextEntry(ZipEntry(path))
        source.inputStream().use { it.copyTo(this) }
        closeEntry()
    }

    private companion object {
        const val MAX_FILE_BYTES = 4L * 1024 * 1024 * 1024
        const val MAX_LIBRARY_BYTES = 16L * 1024 * 1024 * 1024
        const val MAX_ENTRIES = 200_000
    }
}
