package com.aceinandroid.inkflow

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.UUID

/** Only data deltas; this runner never replaces a database or transfers library files. */
internal class NativeIncrementalSync(private val context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("native_incremental_sync", Context.MODE_PRIVATE)

    fun automaticEnabled(): Boolean = preferences.getBoolean("automatic", false)
    fun setAutomaticEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("automatic", enabled).apply()
        IncrementalSyncWorker.cancel(context)
        if (enabled) ProgressSyncWorker.enqueue(context)
    }

    fun lastReport(): String = preferences.getString("last_report", "").orEmpty()

    fun synchronizeAutomatically(store: BookStore): IncrementalSyncReport? {
        if (!automaticEnabled()) return null
        val account = WebDavAccountStore(context).load() ?: return null
        val now = System.currentTimeMillis()
        val previous = preferences.getLong("last_attempt", 0)
        if (now >= previous && now - previous < 60_000) return null
        return NativeSyncIntegration.automatic {
            preferences.edit().putLong("last_attempt", now).apply()
            synchronize(store, WebDavTransport(account.endpoint, account.username, account.password))
        }
    }

    fun synchronizeManually(store: BookStore): IncrementalSyncReport = NativeSyncIntegration.manual {
        val account = WebDavAccountStore(context).load() ?: throw IOException("Connect a WebDAV account first")
        synchronize(store, WebDavTransport(account.endpoint, account.username, account.password))
    }

    /** Vocabulary UI owns a NativeVocabularyStore, so this overload owns a short-lived book helper. */
    fun deleteVocabulary(normalizedWord: String) {
        BookStore(context).use { deleteVocabulary(it, normalizedWord) }
    }

    /** Atomic tombstone + delete boundary, independent of whether automatic sync is enabled. */
    fun deleteVocabulary(store: BookStore, normalizedWord: String) = NativeSyncIntegration.localMutation {
        NativeIncrementalSyncStore(store.writableDatabase, NativeProgressIdentity.getOrCreate(context))
            .deleteVocabulary(normalizedWord)
    }

    /** Caller owns the singleflight lease. Injectable transport keeps package protocol testable. */
    internal fun synchronize(store: BookStore, client: WebDavClient): IncrementalSyncReport {
        val device = NativeProgressIdentity.getOrCreate(context)
        val local = NativeIncrementalSyncStore(store.writableDatabase, device)
        val work = File(context.cacheDir, "incremental-sync-${UUID.randomUUID()}")
        check(work.mkdirs())
        var report = IncrementalSyncReport()
        try {
            val protocol = NativeIncrementalSyncBranch(client, device, work)
            val branches = local.books().map { (key, id) -> Triple("inkflow/data/reading-agent/$key", key, id) } +
                Triple("inkflow/data/vocabulary/v1", null, null)
            for ((directory, key, id) in branches) {
                report += protocol.synchronize(directory, key, merge = { data ->
                    try { local.merge(data, id) }
                    catch (failure: android.database.sqlite.SQLiteException) {
                        throw IOException("Rejected incremental SQLite package", failure)
                    }
                }, capture = { local.capture(key, id) })
            }
            preferences.edit().putString("last_report", "${report.merged}/${report.uploaded}/${report.conflicts}/${report.ignored}")
                .putLong("last_success", System.currentTimeMillis()).apply()
            return report
        } finally { work.deleteRecursively() }
    }

}
