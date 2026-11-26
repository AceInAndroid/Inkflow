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

class ProgressSyncWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    private enum class Outcome { SUCCESS, RETRY, FAILURE }

    override fun doWork(): Result {
        val sync = NativeProgressSync(applicationContext)
        val incremental = NativeIncrementalSync(applicationContext)
        val progressEnabled = sync.automaticEnabled() && sync.account().signedIn
        if (!progressEnabled && !incremental.automaticEnabled()) return Result.success()
        val store = BookStore(applicationContext)
        return try {
            // One lifecycle work item drains both branches without competing for the same gate.
            val progressResult = if (progressEnabled) synchronizeProgress(sync, store) else Outcome.SUCCESS
            val deltaResult = try {
                incremental.synchronizeAutomatically(store)
                Outcome.SUCCESS
            } catch (_: IOException) {
                Outcome.RETRY
            } catch (_: Exception) {
                Outcome.FAILURE
            }
            when {
                progressResult == Outcome.RETRY || deltaResult == Outcome.RETRY -> Result.retry()
                progressResult == Outcome.FAILURE || deltaResult == Outcome.FAILURE -> Result.failure()
                else -> Result.success()
            }
        } finally { store.close() }
    }

    private fun synchronizeProgress(sync: NativeProgressSync, store: BookStore): Outcome = try {
        val attempted = sync.synchronizeAutomatically(store)
        if (attempted != null && sync.hasPendingPositions(store)) Outcome.RETRY else Outcome.SUCCESS
    } catch (failure: ProgressHttpException) {
        if (failure.status == 408 || failure.status == 429 || failure.status >= 500)
            Outcome.RETRY else Outcome.FAILURE
    } catch (_: IOException) {
        Outcome.RETRY
    } catch (_: Exception) {
        Outcome.FAILURE
    }

    companion object {
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork("native-progress-sync")
        }

        fun enqueue(context: Context) {
            val sync = NativeProgressSync(context)
            if ((!sync.automaticEnabled() || !sync.account().signedIn) &&
                !NativeIncrementalSync(context).automaticEnabled()) return
            val request = OneTimeWorkRequestBuilder<ProgressSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "native-progress-sync", ExistingWorkPolicy.KEEP, request)
        }
    }
}
