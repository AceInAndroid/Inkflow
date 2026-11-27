package com.aceinandroid.inkflow.ui.theme

import androidx.activity.compose.LocalActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat

@Suppress("DEPRECATION")
@Composable
fun AppSystemBars() {
    val window = LocalActivity.current?.window ?: return
    val background = MaterialTheme.colorScheme.background
    DisposableEffect(window) {
        val status = window.statusBarColor
        val navigation = window.navigationBarColor
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val lightStatus = controller.isAppearanceLightStatusBars
        val lightNavigation = controller.isAppearanceLightNavigationBars
        onDispose {
            window.statusBarColor = status
            window.navigationBarColor = navigation
            controller.isAppearanceLightStatusBars = lightStatus
            controller.isAppearanceLightNavigationBars = lightNavigation
        }
    }
    SideEffect {
        window.statusBarColor = background.toArgb()
        window.navigationBarColor = background.toArgb()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = background.luminance() > .5f
            isAppearanceLightNavigationBars = background.luminance() > .5f
        }
    }
}
