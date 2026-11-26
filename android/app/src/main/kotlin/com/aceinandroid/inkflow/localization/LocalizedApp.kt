package com.aceinandroid.inkflow.localization

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources

@Composable
fun LocalizedApp(content: @Composable () -> Unit) {
    val original = LocalContext.current
    val context = remember(original) { NativeLocaleContext(original) }
    val locale = NativeLocaleController.locale
    val config = Configuration(LocalConfiguration.current).apply {
        setLocale(locale)
        setLayoutDirection(locale)
    }
    CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides config,
        // Dialogs and bottom sheets supply their own Android Context. Keep their string resources
        // bound to this effective locale as well, including when an already-open window changes.
        LocalResources provides context.resources,
        LocalLayoutDirection provides LayoutDirection.Ltr, content = content)
}
