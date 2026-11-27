package com.aceinandroid.inkflow.ui.reader

import android.app.Dialog
import android.content.Context
import android.view.ContextThemeWrapper
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aceinandroid.inkflow.NativeSettingsBackup
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses an unshown real Window and a layout-free composition: no book, activity launch or model. */
@RunWith(AndroidJUnit4::class)
class ReaderBrightnessDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun manualWindowOverrideRestoresItsExactOriginalValueOnCompositionDisposal() = onMain {
        withWindow(.73f) { window ->
            withComposition { composition ->
                composition.setContent { ReaderWindowBrightnessEffect(window, .2f) }
                assertEquals(.2f, window.attributes.screenBrightness, 0f)
                composition.dispose()
                assertEquals(.73f, window.attributes.screenBrightness, 0f)
            }
        }
    }

    @Test fun followSystemAppliesMinusOneAndStillRestoresThePriorOverride() = onMain {
        withWindow(.61f) { window ->
            withComposition { composition ->
                composition.setContent { ReaderWindowBrightnessEffect(window, null) }
                assertEquals(-1f, window.attributes.screenBrightness, 0f)
                composition.dispose()
                assertEquals(.61f, window.attributes.screenBrightness, 0f)
            }
        }
    }

    @Test fun originallySystemControlledWindowRemainsSystemControlledAfterExit() = onMain {
        withWindow(-1f) { window ->
            withComposition { composition ->
                composition.setContent { ReaderWindowBrightnessEffect(window, 0f) }
                assertEquals(.05f, window.attributes.screenBrightness, 0f)
                composition.dispose()
                assertEquals(-1f, window.attributes.screenBrightness, 0f)
            }
        }
    }

    @Test fun storePersistsBetweenInstancesAndFollowingSystemRetainsLastManualLevel() = isolatedPreferences { context ->
        val first = ReaderBrightnessStore(context)
        assertNull(first.load())
        first.save(.28f)
        assertEquals(.28f, ReaderBrightnessStore(context).load()!!, 0f)
        first.save(null)
        val reopened = ReaderBrightnessStore(context)
        assertNull(reopened.load())
        assertEquals(.28f, reopened.lastManualBrightness(), 0f)
        reopened.save(reopened.lastManualBrightness())
        assertEquals(.28f, first.load()!!, 0f)
    }

    @Test fun brightnessStoreIsExcludedFromPortableBackup() = isolatedPreferences { context ->
        val backupBeforeChange = NativeSettingsBackup.capture(context)
        val store = ReaderBrightnessStore(context)
        store.save(.34f)
        assertFalse(NativeSettingsBackup.capture(context).has(ReaderBrightnessStore.PREFERENCE_NAME))
        NativeSettingsBackup.apply(context, backupBeforeChange)
        assertEquals("Restoring portable settings must preserve device brightness", .34f, store.load()!!, 0f)
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun withWindow(original: Float, block: (android.view.Window) -> Unit) {
        val dialog = Dialog(ContextThemeWrapper(instrumentation.targetContext, android.R.style.Theme_Material_Light_NoActionBar))
        val window = requireNotNull(dialog.window)
        window.attributes = window.attributes.apply { screenBrightness = original }
        try { block(window) } finally { dialog.dismiss() }
    }

    private fun withComposition(block: (Composition) -> Unit) {
        val recomposer = Recomposer(EmptyCoroutineContext)
        val composition = Composition(object : AbstractApplier<Unit>(Unit) {
            override fun insertTopDown(index: Int, instance: Unit) = Unit
            override fun insertBottomUp(index: Int, instance: Unit) = Unit
            override fun remove(index: Int, count: Int) = Unit
            override fun move(from: Int, to: Int, count: Int) = Unit
            override fun onClear() = Unit
        }, recomposer)
        try { block(composition) } finally { composition.dispose(); recomposer.cancel() }
    }

    private fun isolatedPreferences(block: (Context) -> Unit) {
        val target = instrumentation.targetContext
        val name = "reader_brightness_test_${java.util.UUID.randomUUID()}"
        // The store is exercised against real preferences without touching the user's brightness.
        val stores = mutableSetOf<String>()
        val context = object : android.content.ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(requested: String, mode: Int): android.content.SharedPreferences {
                val isolated = "${name}_$requested"
                stores += isolated
                return target.getSharedPreferences(isolated, mode)
            }
        }
        try { block(context) } finally { stores.forEach { target.deleteSharedPreferences(it) } }
    }
}
