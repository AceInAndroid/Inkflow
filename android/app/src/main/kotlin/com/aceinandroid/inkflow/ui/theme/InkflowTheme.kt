package com.aceinandroid.inkflow.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.aceinandroid.inkflow.core.EInkDisplayConfig

object InkflowPalette {
    val Ink = Color(0xFF0F172A)
    val Accent = Color(0xFF1D4ED8)
    val AccentSoft = Color(0xFFDBEAFE)
    val Paper = Color(0xFFF5F6F8)
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

private val StandardDarkScheme = darkColorScheme(
    primary = Color(0xFF93B4FF),
    onPrimary = Color(0xFF082A78),
    primaryContainer = Color(0xFF183B88),
    onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFFB2C6EF),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE3E6EE),
    surface = Color(0xFF1C1F26),
    onSurface = Color(0xFFE3E6EE),
    surfaceVariant = Color(0xFF303640),
    onSurfaceVariant = Color(0xFFBEC6D5),
    outline = Color(0xFF8992A3),
    outlineVariant = Color(0xFF414957),
    surfaceContainerLowest = Color(0xFF0C0E12),
    surfaceContainerLow = Color(0xFF191C22),
    surfaceContainer = Color(0xFF20242C),
    surfaceContainerHigh = Color(0xFF292E38),
    surfaceContainerHighest = Color(0xFF333A46),
)

@Composable
fun InkflowTheme(
    eInkConfig: EInkDisplayConfig = EInkDisplayConfig(),
    colorSchemeOverride: ColorScheme? = null,
    appearance: AppAppearance = AppAppearance.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = appearance == AppAppearance.DARK || (appearance == AppAppearance.SYSTEM && isSystemInDarkTheme())
    val scheme = if (eInkConfig.enabled) EInkLightScheme
        else colorSchemeOverride ?: if (dark) StandardDarkScheme else StandardLightScheme
    CompositionLocalProvider(LocalEInkConfig provides eInkConfig) {
        MaterialTheme(
            colorScheme = scheme,
            content = content,
        )
    }
}
