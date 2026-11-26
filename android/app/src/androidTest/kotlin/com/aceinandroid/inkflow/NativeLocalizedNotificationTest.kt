package com.aceinandroid.inkflow

import android.app.Notification
import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aceinandroid.inkflow.core.localization.AppLanguage
import com.aceinandroid.inkflow.localization.NativeLocaleController
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Builds the actual MediaStyle notification without requiring a device speech engine. */
@RunWith(AndroidJUnit4::class)
class NativeLocalizedNotificationTest {
    @Test fun mediaControlsUseEffectiveLanguageWithoutTranslatingBookTitle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val original = AppLanguage.entries.firstOrNull { it.tag == NativeLocaleController.choice }
        instrumentation.runOnMainSync {
            val service = NarrationPlaybackService()
            ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply {
                isAccessible = true
            }.invoke(service, instrumentation.targetContext)
            service.onCreate()
            try {
                val notification = NarrationPlaybackService::class.java.getDeclaredMethod("notification", NarrationState::class.java)
                    .apply { isAccessible = true }
                val title = NarrationPlaybackService::class.java.getDeclaredField("title").apply { isAccessible = true }
                val expectedStop = mapOf(AppLanguage.ENGLISH to "Stop", AppLanguage.SIMPLIFIED_CHINESE to "停止",
                    AppLanguage.JAPANESE to "停止", AppLanguage.KOREAN to "중지")
                for (language in AppLanguage.entries) {
                    NativeLocaleController.select(language)
                    val result = notification.invoke(service, NarrationState.PLAYING) as Notification
                    val context = NativeLocaleController.applicationContext()
                    assertEquals(context.getString(R.string.read_aloud), result.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
                    assertEquals(context.getString(R.string.narration_playing), result.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
                    assertEquals(context.getString(R.string.pause), result.actions[0].title.toString())
                    assertEquals(context.getString(R.string.stop), result.actions[1].title.toString())
                    // At least the controls are independently translated in each locale.
                    assertEquals(expectedStop.getValue(language), result.actions[1].title.toString())
                    title.set(service, "Untranslated Book / 原文")
                    val withTitle = notification.invoke(service, NarrationState.PLAYING) as Notification
                    assertEquals("Untranslated Book / 原文", withTitle.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
                    title.set(service, "")
                }
            } finally {
                service.onDestroy()
                NativeLocaleController.select(original)
            }
        }
    }
}
