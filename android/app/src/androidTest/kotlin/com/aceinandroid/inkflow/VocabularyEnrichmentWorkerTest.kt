package com.aceinandroid.inkflow

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class VocabularyEnrichmentWorkerTest {
    @Test fun validWordEnqueuesUniqueEnrichmentWork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val word = "enrichmentprobe"
        val manager = WorkManager.getInstance(context)
        try {
            VocabularyEnrichmentWorker.enqueue(context, word)
            val work = manager.getWorkInfosForUniqueWork("native-vocabulary-$word")
                .get(10, TimeUnit.SECONDS)
            assertTrue(work.any { VocabularyEnrichmentWorker.TAG in it.tags })
        } finally {
            manager.cancelUniqueWork("native-vocabulary-$word")
        }
    }
}
