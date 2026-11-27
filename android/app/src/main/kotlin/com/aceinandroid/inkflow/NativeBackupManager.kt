package com.aceinandroid.inkflow

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Typeface
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class NativeBackupManager(context: Context) {
    private val appContext = context.applicationContext
    private val stage = File(appContext.filesDir, ".native-restore-stage")
    private val rollback = File(appContext.filesDir, ".native-restore-old")
    private val marker = File(appContext.filesDir, ".native-restore-marker")
    private val database = appContext.getDatabasePath("app_database.db")
    private val flutterPreferences = File(appContext.filesDir, "flutter_preferences_backup.json")
    private val directories = listOf("file", "cover", "font", "bgimg")
    private val nativeFontName = Regex("[0-9a-f-]{36}\\.(ttf|otf|ttc)")

    fun export(uri: Uri, store: BookStore) = synchronized(lock) {
        recoverInterruptedLocked()
        store.close()
        if (!database.isFile) throw IOException("Library database is missing")
        val nativeSettings = NativeSettingsBackup.capture(appContext)
        val output = appContext.contentResolver.openOutputStream(uri, "w")
            ?: throw IOException("Cannot create backup")
        ZipOutputStream(output.buffered()).use { zip ->
            zip.addFile("databases/app_database.db", database)
            for (directoryName in directories) {
                val directory = File(appContext.filesDir, directoryName)
                if (!directory.isDirectory) continue
                directory.walkTopDown().filter(File::isFile).forEach { file ->
                    val path = file.relativeTo(appContext.filesDir).path.replace(File.separatorChar, '/')
                    zip.addFile(path, file)
                }
            }
            if (flutterPreferences.isFile) {
                val legacy = NativeLegacyPreferenceFilter.portable(JSONObject(flutterPreferences.readText(Charsets.UTF_8)))
                zip.addBytes("anx_shared_prefs.json", legacy.toString().toByteArray(Charsets.UTF_8))
            }
            zip.addBytes(NativeSettingsBackup.ENTRY, nativeSettings.toString().toByteArray(Charsets.UTF_8))
            zip.putNextEntry(ZipEntry("native/fonts/"))
            zip.closeEntry()
            File(appContext.filesDir, "fonts").listFiles()?.filter { it.isFile && nativeFontName.matches(it.name) }
                ?.forEach { zip.addFile("native/fonts/${it.name}", it) }
        }
    }

    fun restore(uri: Uri, store: BookStore, preserveLocalPreferences: Boolean = false) = synchronized(lock) {
        recoverInterruptedLocked()
        delete(stage)
        delete(rollback)
        try {
            extract(uri)
            validateDatabase()
            val flutterSettings = validatePreferences()
            val nativeSettings = validateNativeSettings()
            store.close()
            val targets = targets().filter { nativeSettings != null || it.first != "native-fonts" }
            val original = JSONObject()
            targets.forEach { (name, target) -> original.put(name, target.exists()) }
            rollback.mkdirsOrThrow()
            writeSynced(File(rollback, "native-settings.json"), NativeSettingsBackup.capture(appContext).toString())
            writeMarker(original)
            targets.forEach { (name, target) ->
                if (target.exists()) move(target, File(rollback, name))
                val staged = stagedFile(name)
                if (staged.exists()) move(staged, target)
            }
            if (nativeSettings != null) NativeSettingsBackup.apply(appContext, nativeSettings)
            else {
                val rules = appContext.getSharedPreferences("native_txt_rules", Context.MODE_PRIVATE)
                val ruleEditor = rules.edit()
                if (preserveLocalPreferences) {
                    rules.all.keys.filter { it.startsWith("book:") }.forEach(ruleEditor::remove)
                } else ruleEditor.clear()
                if (!ruleEditor.commit()) throw IOException("Cannot update TXT rules")
                if (flutterSettings != null) NativeSettingsBackup.applyFlutterPreferences(appContext, flutterSettings)
                for (name in listOf("native_reader_positions", "native_reader_text_positions")) {
                    if (!appContext.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit())
                        throw IOException("Cannot clear old reading positions")
                }
            }
            if (!marker.delete()) throw IOException("Cannot finish backup restore")
        } catch (failure: Exception) {
            if (marker.exists()) {
                try {
                    recoverInterruptedLocked()
                } catch (recovery: Exception) {
                    failure.addSuppressed(recovery)
                }
            }
            throw failure
        } finally {
            if (!marker.exists()) {
                delete(stage)
                delete(rollback)
            }
        }
    }

    fun recoverInterrupted() = synchronized(lock) { recoverInterruptedLocked() }

    private fun recoverInterruptedLocked() {
        if (!marker.exists()) {
            delete(stage)
            delete(rollback)
            delete(File(appContext.filesDir, ".native-restore-marker.tmp"))
            return
        }
        val original = JSONObject(marker.readText())
        targets().forEach { (name, target) ->
            val previous = File(rollback, name)
            if (previous.exists()) {
                delete(target)
                move(previous, target)
            } else if (!original.optBoolean(name, true)) {
                delete(target)
            }
        }
        val previousSettings = File(rollback, "native-settings.json")
        if (previousSettings.isFile) NativeSettingsBackup.apply(appContext, NativeSettingsBackup.read(previousSettings))
        if (!marker.delete()) throw IOException("Cannot finish backup recovery")
        delete(stage)
        delete(rollback)
    }

    private fun extract(uri: Uri) {
        stage.mkdirsOrThrow()
        val allowedFiles = setOf(
            "databases/app_database.db", "databases/app_database.db-wal",
            "databases/app_database.db-shm", "anx_shared_prefs.json", NativeSettingsBackup.ENTRY,
        )
        var total = 0L
        var entries = 0
        val source = appContext.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open backup")
        ZipInputStream(source.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                if (entries > 200_000) throw IOException("Backup has too many files")
                val name = entry.name.removeSuffix("/")
                val parts = name.split('/')
                if (parts.isEmpty() || parts.any { it.isEmpty() || it == "." || it == ".." } ||
                    name.startsWith('/') || '\\' in name || '\u0000' in name) {
                    throw IOException("Invalid backup path")
                }
                val nativeFont = parts.size == 3 && parts[0] == "native" && parts[1] == "fonts" &&
                    nativeFontName.matches(parts[2]) && !entry.isDirectory
                val allowed = name in allowedFiles || parts.first() in directories || nativeFont ||
                    (entry.isDirectory && name in setOf("native", "native/fonts"))
                if (!allowed) {
                    if (parts.first() != "databases") throw IOException("Unsupported backup entry: $name")
                    val buffer = ByteArray(64 * 1024)
                    var size = 0L
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        size += count
                        total += count
                        if (size > 4L * 1024 * 1024 * 1024 || total > 16L * 1024 * 1024 * 1024)
                            throw IOException("Backup is too large")
                    }
                    zip.closeEntry()
                    continue
                }
                val target = File(stage, name)
                if (!target.canonicalPath.startsWith(stage.canonicalPath + File.separator))
                    throw IOException("Invalid backup path")
                if (entry.isDirectory) target.mkdirsOrThrow()
                else {
                    target.parentFile!!.mkdirsOrThrow()
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var size = 0L
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            size += count
                            total += count
                            if ((name == "anx_shared_prefs.json" && size > 8L * 1024 * 1024) ||
                                (name == NativeSettingsBackup.ENTRY && size > 1024 * 1024) ||
                                (nativeFont && size > 64L * 1024 * 1024) ||
                                size > 4L * 1024 * 1024 * 1024 || total > 16L * 1024 * 1024 * 1024)
                                throw IOException("Backup is too large")
                            output.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        if (entries == 0 || !File(stage, "databases/app_database.db").isFile)
            throw IOException("Backup database is missing")
        directories.forEach { File(stage, it).mkdirsOrThrow() }
    }

    private fun validateDatabase() {
        val file = File(stage, "databases/app_database.db")
        if (file.length() < 1024) throw IOException("Backup database is too small")
        val header = ByteArray(16)
        file.inputStream().use { if (it.read(header) != header.size) throw IOException("Invalid database") }
        if (!header.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)))
            throw IOException("Invalid database")
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            if (db.version != 22) throw IOException("Unsupported database version: ${db.version}")
            db.rawQuery("PRAGMA integrity_check", null).use {
                if (!it.moveToFirst() || it.getString(0) != "ok") throw IOException("Backup database is corrupt")
            }
            for (table in listOf("tb_books", "tb_notes", "tb_reading_time")) {
                db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
                    arrayOf(table)).use { if (!it.moveToFirst()) throw IOException("Missing $table") }
            }
            db.rawQuery("SELECT file_path FROM tb_books WHERE is_deleted = 0", null).use { rows ->
                while (rows.moveToNext()) {
                    val path = rows.getString(0) ?: throw IOException("Book path is missing")
                    val parts = path.split('/')
                    if (parts.size < 2 || parts.first() != "file" ||
                        parts.any { it.isEmpty() || it == "." || it == ".." } || '\\' in path ||
                        !File(stage, path).isFile) throw IOException("Backup book file is missing or invalid")
                }
            }
            db.rawQuery("PRAGMA journal_mode=DELETE", null).use {
                if (!it.moveToFirst() || !it.getString(0).equals("delete", true))
                    throw IOException("Cannot checkpoint backup database")
            }
        } finally {
            db.close()
        }
        delete(File(stage, "databases/app_database.db-wal"))
        delete(File(stage, "databases/app_database.db-shm"))
    }

    private fun validatePreferences(): JSONObject? {
        val file = File(stage, "anx_shared_prefs.json")
        if (!file.exists()) return null
        return try {
            NativeLegacyPreferenceFilter.portable(JSONObject(file.readText(Charsets.UTF_8))).also {
                writeSynced(file, it.toString())
            }
        } catch (failure: Exception) {
            throw IOException("Invalid backup preferences", failure)
        }
    }

    private fun validateNativeSettings(): JSONObject? {
        val file = File(stage, NativeSettingsBackup.ENTRY)
        if (!file.exists()) return null
        val settings = NativeSettingsBackup.read(file)
        val fonts = File(stage, "native/fonts")
        fonts.mkdirsOrThrow()
        fonts.listFiles()?.forEach { font ->
            if (!font.isFile || !nativeFontName.matches(font.name) || font.length() > 64L * 1024 * 1024)
                throw IOException("Invalid native backup font")
            try {
                Typeface.createFromFile(font)
            } catch (failure: Exception) {
                throw IOException("Invalid native backup font", failure)
            }
        }
        NativeSettingsBackup.selectedFontId(settings)?.let { id ->
            if (!nativeFontName.matches(id) || !File(fonts, id).isFile)
                throw IOException("Native backup font is missing")
        }
        NativeSettingsBackup.selectedBackgroundImage(settings)?.let { image ->
            if (image.type == "localFile" && !File(stage, "bgimg/${image.path}").isFile)
                throw IOException("Native backup background image is missing")
            if (image.type == "localFile" && image.nightPath != null &&
                !File(stage, "bgimg/${image.nightPath}").isFile)
                throw IOException("Native backup night background image is missing")
        }
        return settings
    }

    private fun targets(): List<Pair<String, File>> = directories.map { it to File(appContext.filesDir, it) } +
        listOf("database" to database, "database-wal" to File("${database.path}-wal"),
            "database-shm" to File("${database.path}-shm"), "preferences" to flutterPreferences,
            "native-fonts" to File(appContext.filesDir, "fonts"))

    private fun stagedFile(name: String): File = if (name.startsWith("database")) {
        File(stage, "databases/app_database.db${name.removePrefix("database")}")
    } else if (name == "preferences") File(stage, "anx_shared_prefs.json")
    else if (name == "native-fonts") File(stage, "native/fonts")
    else File(stage, name)

    private fun writeMarker(original: JSONObject) {
        val temporary = File(appContext.filesDir, ".native-restore-marker.tmp")
        writeSynced(temporary, original.toString())
        move(temporary, marker)
    }

    private fun writeSynced(file: File, text: String) {
        FileOutputStream(file).use {
            it.write(text.toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
    }

    private fun ZipOutputStream.addFile(path: String, file: File) {
        putNextEntry(ZipEntry(path))
        file.inputStream().use { it.copyTo(this) }
        closeEntry()
    }

    private fun ZipOutputStream.addBytes(path: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(path))
        write(bytes)
        closeEntry()
    }

    private fun move(source: File, target: File) {
        target.parentFile!!.mkdirsOrThrow()
        if (target.exists() || !source.renameTo(target)) throw IOException("Cannot move backup data")
    }

    private fun delete(file: File) {
        if (file.exists() && !file.deleteRecursively()) throw IOException("Cannot remove temporary backup data")
    }

    private fun File.mkdirsOrThrow() {
        if (!isDirectory && !mkdirs()) throw IOException("Cannot create backup directory")
    }

    private companion object {
        val lock = Any()
    }
}
