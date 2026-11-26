package com.aceinandroid.inkflow.ui.ai

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/** Keep the reader subtree in the same composition slot when the workspace opens or resizes. */
@Composable
fun ReadingWorkspaceHost(panelOpen: Boolean, content: @Composable (docked: Boolean, readerModifier: Modifier) -> Unit) {
    val docked = panelOpen && LocalConfiguration.current.screenWidthDp >= 840
    Box(Modifier.fillMaxSize()) {
        content(docked, Modifier.fillMaxSize().padding(end = if (docked) 400.dp else 0.dp))
    }
}
