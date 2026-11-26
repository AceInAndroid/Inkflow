package com.aceinandroid.inkflow

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class ProgressSyncAccount(val endpoint: String, val signedIn: Boolean)

open class NativeProgressSync(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("native_progress_sync", Context.MODE_PRIVATE)

    fun account(): ProgressSyncAccount = ProgressSyncAccount(
        preferences.getString("endpoint", "").orEmpty(),
        !preferences.getString("token", "").isNullOrBlank(),
    )

    fun automaticEnabled(): Boolean = preferences.getBoolean("automatic", false)

    fun setAutomaticEnabled(enabled: Boolean) {
        check(preferences.edit().putBoolean("automatic", enabled).commit()) {
            "Cannot save automatic sync setting"
        }
        if (!enabled) ProgressSyncWorker.cancel(appContext)
    }

    fun synchronizeAutomatically(store: BookStore): Int? = synchronized(lock) {
        if (!automaticEnabled() || !account().signedIn) return@synchronized null
        val lease = NativeSyncIntegration.gate.tryAcquire(com.aceinandroid.inkflow.core.sync.SyncGate.Trigger.AUTOMATIC)
            ?: return@synchronized null
        lease.use {
            val now = System.currentTimeMillis()
            if (!hasPendingPositions(store) && now - preferences.getLong("last_auto_attempt", 0) < 5 * 60_000L)
                return@synchronized null
            try {
                synchronize(store).also {
                    check(preferences.edit().putLong("last_auto_attempt", now).commit()) { "Cannot save automatic sync time" }
                }
            } catch (failure: Exception) {
                NativeSyncIntegration.gate.deferAutomatic()
                throw failure
            }
        }
    }

    internal fun hasPendingPositions(store: BookStore): Boolean =
        latestOwnPosition(store.writableDatabase, NativeProgressIdentity.getOrCreate(appContext)) >
            preferences.getLong("last_uploaded_at", 0)

    fun onLibraryRestored() {
        check(preferences.edit().remove("last_uploaded_at").remove("last_auto_attempt").commit()) {
            "Cannot reset progress sync after restore"
        }
    }

    fun signIn(endpoint: String, username: String, password: String) {
        val service = normalizeEndpoint(endpoint)
        require(username.isNotBlank() && password.isNotBlank()) { "Username and password are required" }
        val response = request(service, "/v1/auth/login", "POST", null, JSONObject().apply {
            put("username", username.trim())
            put("password", password)
        })
        val token = response.optString("accessToken")
        val expiry = parseExpiry(response.optString("expiresAt"))
        require(token.isNotBlank() && expiry > System.currentTimeMillis()) { "Invalid login response" }
        check(preferences.edit().putString("endpoint", service).putString("token", token)
            .putLong("expires_at", expiry).remove("cursor").remove("last_auto_attempt")
            .remove("last_uploaded_at").commit()) {
            "Cannot save sync account"
        }
    }

    fun signOut() {
        val endpoint = preferences.getString("endpoint", "").orEmpty()
        val token = preferences.getString("token", "").orEmpty()
        try {
            if (endpoint.isNotBlank() && token.isNotBlank())
                request(endpoint, "/v1/auth/logout", "POST", token, null)
        } finally {
            clearSession()
        }
    }

    fun synchronize(store: BookStore): Int = synchronized(lock) {
        val endpoint = normalizeEndpoint(preferences.getString("endpoint", "").orEmpty())
        val token = preferences.getString("token", "").orEmpty()
        check(token.isNotBlank()) { "Sign in to progress sync first" }
        if (preferences.getLong("expires_at", 0) <= System.currentTimeMillis()) {
            clearSession()
            error("Progress sync session expired")
        }
        val deviceId = NativeProgressIdentity.getOrCreate(appContext)
        val db = store.writableDatabase
        seedOwnPositions(db, deviceId)
        val uploadCutoff = latestOwnPosition(db, deviceId)
        try {
            val health = request(endpoint, "/health", "GET", null, null)
            check(health.optInt("protocolVersion") == 1) { "Unsupported progress sync protocol" }
            val cursor = preferences.getString("cursor", "").orEmpty()
            val response = try {
                changes(endpoint, token, cursor)
            } catch (failure: ProgressHttpException) {
                if (cursor.isNotBlank() && failure.status == 409 && failure.code == "cursor_expired")
                    changes(endpoint, token, "") else throw failure
            }
            val changes = response.optJSONArray("changes") ?: throw IOException("Invalid progress sync changes")
            val nextCursor = response.optString("nextCursor")
            if (nextCursor.isBlank() || !nextCursor.all(Char::isDigit))
                throw IOException("Invalid progress sync cursor")
            val merged = mergeChanges(db, changes, deviceId)
            for (book in activeBooks(db)) {
                val own = db.rawQuery("""
                    SELECT cfi, progress, chapter_href, chapter_title, updated_at
                    FROM tb_book_device_positions WHERE book_id = ? AND device_id = ?
                """.trimIndent(), arrayOf(book.id.toString(), deviceId)).use {
                    if (!it.moveToFirst() || it.getString(0).isNullOrBlank()) null else JSONObject().apply {
                        put("schemaVersion", 1)
                        put("bookKey", book.key)
                        put("deviceId", deviceId)
                        put("locator", JSONObject().put("type", "cfi").put("value", it.getString(0)))
                        put("progress", it.getDouble(1))
                        if (!it.isNull(2)) put("chapterHref", it.getString(2))
                        if (!it.isNull(3)) put("chapterTitle", it.getString(3))
                        put("updatedAt", it.getLong(4))
                    }
                } ?: continue
                request(endpoint, "/v1/books/${Uri.encode(book.key)}/devices/${Uri.encode(deviceId)}/progress",
                    "PUT", token, own)
            }
            check(preferences.edit().putString("cursor", nextCursor)
                .putLong("last_uploaded_at", uploadCutoff).commit()) {
                "Cannot save sync cursor"
            }
            merged
        } catch (failure: ProgressHttpException) {
            if (failure.status == 401) clearSession()
            throw failure
        }
    }

    internal fun mergeChanges(db: SQLiteDatabase, changes: JSONArray, ownId: String): Int {
        val books = activeBooks(db).associateBy { it.key }
        var merged = 0
        db.beginTransaction()
        try {
            for (index in 0 until changes.length()) {
                val change = changes.optJSONObject(index) ?: continue
                if (change.optBoolean("deleted")) continue
                val book = books[change.optString("bookKey")] ?: continue
                val device = change.optString("deviceId").takeIf { it.isNotBlank() && it.length <= 128 }
                    ?: continue
                val locator = change.optJSONObject("locator") ?: continue
                if (locator.optString("type") != "cfi") continue
                val cfi = locator.optString("value").takeIf { it.isNotBlank() && it.length <= 8192 }
                    ?: continue
                val progress = change.optDouble("progress", Double.NaN)
                val updatedAt = change.optLong("updatedAt", -1)
                if (!progress.isFinite() || progress !in 0.0..1.0 || updatedAt < 0) continue
                val previous = db.rawQuery("""
                    SELECT updated_at FROM tb_book_device_positions WHERE book_id = ? AND device_id = ?
                """.trimIndent(), arrayOf(book.id.toString(), device)).use {
                    if (it.moveToFirst()) it.getLong(0) else -1L
                }
                if (updatedAt <= previous || (device == ownId && previous >= 0)) continue
                db.insertWithOnConflict("tb_book_device_positions", null, ContentValues().apply {
                    put("book_id", book.id)
                    put("device_id", device)
                    put("cfi", cfi)
                    put("progress", progress)
                    put("chapter_href", (change.opt("chapterHref") as? String)?.takeIf(String::isNotBlank))
                    put("chapter_title", (change.opt("chapterTitle") as? String)?.takeIf(String::isNotBlank))
                    put("updated_at", updatedAt)
                }, SQLiteDatabase.CONFLICT_REPLACE)
                merged++
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return merged
    }

    private fun seedOwnPositions(db: SQLiteDatabase, deviceId: String) {
        val missing = db.rawQuery("""
            SELECT b.id, b.last_read_position, COALESCE(b.reading_percentage, 0)
            FROM tb_books b LEFT JOIN tb_book_device_positions p
              ON p.book_id = b.id AND p.device_id = ?
            WHERE b.is_deleted = 0 AND b.last_read_position != '' AND p.device_id IS NULL
        """.trimIndent(), arrayOf(deviceId)).use { rows ->
            buildList {
                while (rows.moveToNext()) add(Triple(rows.getLong(0), rows.getString(1),
                    rows.getDouble(2).coerceIn(0.0, 1.0)))
            }
        }
        db.beginTransaction()
        try {
            for ((bookId, cfi, progress) in missing) db.insertWithOnConflict(
                "tb_book_device_positions", null, ContentValues().apply {
                    put("book_id", bookId)
                    put("device_id", deviceId)
                    put("cfi", cfi)
                    put("progress", progress)
                    put("updated_at", System.currentTimeMillis())
                }, SQLiteDatabase.CONFLICT_IGNORE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun latestOwnPosition(db: SQLiteDatabase, deviceId: String): Long = db.rawQuery(
        "SELECT MAX(updated_at) FROM tb_book_device_positions WHERE device_id = ?",
        arrayOf(deviceId)).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L }

    private data class SyncBook(val id: Long, val key: String)

    private fun activeBooks(db: SQLiteDatabase): List<SyncBook> = db.query("tb_books",
        arrayOf("id", "file_md5"), "is_deleted = 0", null, null, null, null).use { rows ->
        buildList {
            while (rows.moveToNext()) {
                val id = rows.getLong(0)
                add(SyncBook(id, rows.getString(1)?.trim()?.lowercase()?.takeIf(String::isNotBlank)
                    ?: "id-$id"))
            }
        }
    }

    private fun changes(endpoint: String, token: String, cursor: String): JSONObject = request(endpoint,
        "/v1/changes" + if (cursor.isBlank()) "" else "?cursor=${Uri.encode(cursor)}",
        "GET", token, null)

    internal open fun request(endpoint: String, path: String, method: String, token: String?, body: JSONObject?): JSONObject {
        val connection = URL(endpoint + path).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.requestMethod = method
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Accept", "application/json")
        if (!token.isNullOrBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
        try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (output.size() + read > 2_097_152)
                        throw IOException("Progress sync response is too large")
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            } ?: ByteArray(0)
            if (bytes.size > 2_097_152) throw IOException("Progress sync response is too large")
            val response = if (bytes.isEmpty()) JSONObject() else JSONObject(String(bytes, Charsets.UTF_8))
            if (status !in 200..299) throw ProgressHttpException(status,
                response.optJSONObject("error")?.optString("code").orEmpty())
            return response
        } finally { connection.disconnect() }
    }

    private fun clearSession() {
        check(preferences.edit().remove("token").remove("expires_at").remove("cursor")
            .remove("automatic").remove("last_auto_attempt").remove("last_uploaded_at").commit()) {
            "Cannot clear sync account"
        }
        ProgressSyncWorker.cancel(appContext)
    }

    companion object {
        private val lock = Any()

        internal fun normalizeEndpoint(value: String): String {
            val uri = runCatching { URI(value.trim()) }.getOrNull()
            require(uri != null && uri.scheme == "https" && !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
                "A valid HTTPS service URL is required"
            }
            return value.trim().trimEnd('/')
        }

        private fun parseExpiry(value: String): Long = runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }.parse(value)?.time ?: 0L
        }.getOrDefault(0L)
    }
}

class ProgressHttpException(val status: Int, val code: String) : IOException("Progress sync HTTP $status ($code)")
