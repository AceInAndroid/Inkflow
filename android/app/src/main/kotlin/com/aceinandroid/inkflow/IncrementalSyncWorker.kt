package com.aceinandroid.inkflow

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import java.io.IOException
import java.util.concurrent.TimeUnit

class IncrementalSyncWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val sync = NativeIncrementalSync(applicationContext)
        if (!sync.automaticEnabled()) return Result.success()
        val store = BookStore(applicationContext)
        return try {
            sync.synchronizeAutomatically(store) // Deferred reading/busy requests complete quietly.
            Result.success()
        } catch (_: IOException) {
            Result.retry()
        } catch (_: Exception) {
            Result.failure()
        } finally { store.close() }
    }

    companion object {
        private const val NAME = "native-incremental-sync"
        fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(NAME) }
        fun enqueue(context: Context) {
            if (!NativeIncrementalSync(context).automaticEnabled()) return
            val request = OneTimeWorkRequestBuilder<IncrementalSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
