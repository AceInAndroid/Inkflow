package com.aceinandroid.inkflow.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.aceinandroid.inkflow.core.EInkDisplayConfig

object InkflowPalette {
    val Ink = Color(0xFF0F172A)
    val Accent = Color(0xFF1D4ED8)
    val AccentSoft = Color(0xFFDBEAFE)
    val Paper = Color(0xFFF6F8FC)
    val SurfaceCard = Color(0xFFFFFFFF)
    val Muted = Color(0xFF52637A)
    val Border = Color(0xFFD8E2F0)
}

val LocalEInkConfig = staticCompositionLocalOf { EInkDisplayConfig() }

private val StandardLightScheme: ColorScheme = lightColorScheme(
    primary = InkflowPalette.Accent,
    onPrimary = Color.White,
    primaryContainer = InkflowPalette.AccentSoft,
    onPrimaryContainer = InkflowPalette.Ink,
    secondary = Color(0xFF2563EB),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0ECFF),
    onSecondaryContainer = InkflowPalette.Ink,
    background = InkflowPalette.Paper,
    onBackground = InkflowPalette.Ink,
    surface = InkflowPalette.SurfaceCard,
    onSurface = InkflowPalette.Ink,
    surfaceVariant = Color(0xFFE9EFF8),
    onSurfaceVariant = InkflowPalette.Muted,
    outline = Color(0xFF94A8C4),
    outlineVariant = InkflowPalette.Border,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF2F6FC),
    surfaceContainer = Color(0xFFECF1F9),
    surfaceContainerHigh = Color(0xFFE4ECF7),
    surfaceContainerHighest = Color(0xFFDCE6F5),
)

private val EInkLightScheme: ColorScheme = lightColorScheme(
    primary = Color.Black,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEAEAEA),
    onPrimaryContainer = Color.Black,
    secondary = Color.Black,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDEDED),
    onSecondaryContainer = Color.Black,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFF3F3F3),
    onSurfaceVariant = Color(0xFF222222),
    outline = Color.Black,
    outlineVariant = Color(0xFF444444),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF5F5F5),
    surfaceContainerHighest = Color(0xFFEBEBEB),
)

@Composable
fun InkflowTheme(
    eInkConfig: EInkDisplayConfig = EInkDisplayConfig(),
    colorSchemeOverride: ColorScheme? = null,
    content: @Composable () -> Unit,
) {
    val scheme = colorSchemeOverride ?: if (eInkConfig.enabled) EInkLightScheme else StandardLightScheme
    CompositionLocalProvider(LocalEInkConfig provides eInkConfig) {
        MaterialTheme(
            colorScheme = scheme,
            content = content,
        )
    }
}
