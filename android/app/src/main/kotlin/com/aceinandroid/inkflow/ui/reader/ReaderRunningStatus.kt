package com.aceinandroid.inkflow.ui.reader

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.aceinandroid.inkflow.ReaderAppearance
import kotlinx.coroutines.delay
import java.util.Date

@Composable
internal fun ReaderRunningStatus(appearance: ReaderAppearance, progress: String = "") {
    val context = LocalContext.current
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val status by produceState("", appearance.showTime, appearance.showBattery, progress, locale) {
        do {
            val parts = mutableListOf(progress)
            if (appearance.showTime) parts += android.text.format.DateFormat.getBestDateTimePattern(locale,
                if (android.text.format.DateFormat.is24HourFormat(context)) "Hm" else "hm").let {
                    java.text.SimpleDateFormat(it, locale).format(Date())
                }
            if (appearance.showBattery) {
                val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (level >= 0 && scale > 0) parts += "${level * 100 / scale}%"
            }
            value = parts.filter { it.isNotBlank() }.joinToString(" · ")
            if (!appearance.showTime && !appearance.showBattery) break
            delay(60_000)
        } while (true)
    }
    Text(status, color = appearance.foreground.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
}
