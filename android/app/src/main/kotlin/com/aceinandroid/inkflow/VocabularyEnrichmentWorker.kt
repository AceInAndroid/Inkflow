package com.aceinandroid.inkflow

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import java.io.IOException
import java.util.concurrent.TimeUnit

class VocabularyEnrichmentWorker(context: Context, parameters: WorkerParameters) :
    Worker(context, parameters) {
    override fun doWork(): Result {
        val word = inputData.getString(WORD) ?: return Result.failure()
        val store = BookStore(applicationContext)
        return try {
            val vocabulary = NativeVocabularyStore(store)
            if (!vocabulary.containsNormalizedWord(word)) return Result.success()
            val entry = try { NativeEnglishDictionary().lookup(word) }
            catch (_: IOException) { return Result.retry() }
            if (entry != null) vocabulary.enrich(entry)
            Result.success()
        } catch (_: Exception) { Result.failure() }
        finally { store.close() }
    }

    companion object {
        private const val WORD = "word"
        const val TAG = "native-vocabulary-enrichment"

        fun enqueue(context: Context, word: String) {
            val normalized = NativeEnglishDictionary.lookupWord(word) ?: return
            val request = OneTimeWorkRequestBuilder<VocabularyEnrichmentWorker>()
                .setInputData(Data.Builder().putString(WORD, normalized).build())
                .addTag(TAG)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "native-vocabulary-$normalized", ExistingWorkPolicy.KEEP, request)
        }
    }
}
