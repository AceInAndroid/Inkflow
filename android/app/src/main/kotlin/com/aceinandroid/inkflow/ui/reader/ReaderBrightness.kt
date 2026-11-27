package com.aceinandroid.inkflow.ui.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import com.aceinandroid.inkflow.R
import kotlin.math.roundToInt

/** Device-only preferences: intentionally outside NativeSettingsBackup's portable store list.
 * Android system backup is also disabled by this application's manifest.
 */
class ReaderBrightnessStore(context: Context) {
    internal val preferences: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFERENCE_NAME, Context.MODE_PRIVATE)

    /** null follows the system; a manual override is always between 5% and 100%. */
    fun load(): Float? {
        val saved = preferences.all
        if (saved[FOLLOW_SYSTEM] as? Boolean != false) return null
        return normalizedReaderBrightness(saved[LEVEL] as? Float)
    }

    fun lastManualBrightness(): Float = normalizedReaderBrightness(preferences.all[LEVEL] as? Float) ?: .5f

    fun save(brightness: Float?) {
        val normalized = normalizedReaderBrightness(brightness)
        preferences.edit().apply {
            putBoolean(FOLLOW_SYSTEM, normalized == null)
            if (normalized != null) putFloat(LEVEL, normalized)
        }.apply()
    }

    internal companion object {
        const val PREFERENCE_NAME = "native_reader_brightness_device"
        private const val FOLLOW_SYSTEM = "follow_system"
        private const val LEVEL = "level"
    }
}

internal fun normalizedReaderBrightness(brightness: Float?): Float? =
    brightness?.takeIf { it.isFinite() }?.coerceIn(.05f, 1f)

/** One owner per window. Parameter updates must not recapture the restoration value. */
internal class ReaderBrightnessOverride(
    private val original: Float,
    private val write: (Float) -> Unit,
) : AutoCloseable {
    private var closed = false

    fun update(brightness: Float?) {
        if (!closed) write(normalizedReaderBrightness(brightness) ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
    }

    override fun close() {
        if (!closed) {
            closed = true
            write(original)
        }
    }
}

/** Observe the local preference, including changes made by ReaderBrightnessDialog. */
@Composable
fun rememberReaderBrightness(): Float? {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { ReaderBrightnessStore(context) }
    var brightness by remember(store) { mutableStateOf(store.load()) }
    DisposableEffect(store) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> brightness = store.load() }
        store.preferences.registerOnSharedPreferenceChangeListener(listener)
        // Subscribe before rereading, so a save between composition and this effect cannot be missed.
        brightness = store.load()
        onDispose { store.preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return brightness
}

/** Place once while the reader is present. Automatically observes device-local preferences. */
@Composable
fun ReaderWindowBrightness() = ReaderWindowBrightness(rememberReaderBrightness())

/** Explicit-state variant. null means follow system; disposal restores the original window value. */
@Composable
fun ReaderWindowBrightness(brightness: Float?) {
    val view = LocalView.current
    val context = LocalContext.current
    val window = (view.parent as? DialogWindowProvider)?.window ?: context.readerBrightnessActivity()?.window
    if (window != null) ReaderWindowBrightnessEffect(window, brightness)
}

@Composable
internal fun ReaderWindowBrightnessEffect(window: Window, brightness: Float?) {
    val owner = remember(window) {
        ReaderBrightnessOverride(window.attributes.screenBrightness) { value ->
            window.attributes = window.attributes.apply { screenBrightness = value }
        }
    }
    DisposableEffect(owner) { onDispose { owner.close() } }
    SideEffect { owner.update(brightness) }
}

private fun Context.readerBrightnessActivity(): Activity? {
    var candidate: Context = this
    while (candidate is ContextWrapper) {
        if (candidate is Activity) return candidate
        val base = candidate.baseContext
        if (base === candidate) break
        candidate = base
    }
    return candidate as? Activity
}

/** Changes are immediately applied and retained; dismissing closes the panel without rolling them back.
 * The reader's ReaderWindowBrightness() observes the same store. This dialog also previews its own
 * window, which is distinct from the underlying activity window.
 */
@Composable
fun ReaderBrightnessDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { ReaderBrightnessStore(context) }
    val brightness = rememberReaderBrightness()
    val level = brightness ?: store.lastManualBrightness()
    val followSystemLabel = stringResource(R.string.inkflow_reader_brightness_follow_system)
    val levelLabel = stringResource(R.string.inkflow_reader_brightness_level, (level * 100).roundToInt())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.inkflow_reader_brightness_title)) },
        text = {
            ReaderWindowBrightness(brightness)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.inkflow_reader_brightness_summary), style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(followSystemLabel, Modifier.weight(1f).padding(end = 12.dp))
                    Switch(checked = brightness == null,
                        modifier = Modifier.semantics { contentDescription = followSystemLabel },
                        onCheckedChange = { follow ->
                        store.save(if (follow) null else store.lastManualBrightness())
                    })
                }
                Text(if (brightness == null) followSystemLabel else levelLabel)
                Slider(value = level, onValueChange = store::save, valueRange = .05f..1f,
                    enabled = brightness != null,
                    modifier = Modifier.semantics { contentDescription = levelLabel })
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.inkflow_reader_brightness_done)) }
        },
    )
}
