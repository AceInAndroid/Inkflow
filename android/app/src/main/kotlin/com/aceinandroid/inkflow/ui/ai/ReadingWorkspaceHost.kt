package com.aceinandroid.inkflow.ui.ai

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Keep the reader in one composition slot, using measured window space during resize. */
@Composable
fun ReadingWorkspaceHost(panelOpen: Boolean, panelWidth: Float = 400f,
    content: @Composable (docked: Boolean, readerModifier: Modifier, availableWidth: Dp) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = panelWidth.coerceIn(380f, 440f).dp
        val docked = panelOpen && maxWidth >= 840.dp && maxWidth - width >= 360.dp
        content(docked, Modifier.fillMaxSize().padding(end = if (docked) width else 0.dp), maxWidth)
    }
}
