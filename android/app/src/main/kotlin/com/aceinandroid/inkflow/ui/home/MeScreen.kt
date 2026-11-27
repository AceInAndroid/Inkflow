package com.aceinandroid.inkflow.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aceinandroid.inkflow.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeScreen(summary: com.aceinandroid.inkflow.ReadingSummary, onNotes: () -> Unit, onVocabulary: () -> Unit, onStatistics: () -> Unit, onSettings: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.home_me)) }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(stringResource(R.string.home_me_summary), color = MaterialTheme.colorScheme.onSurfaceVariant)
            val numbers = java.text.NumberFormat.getIntegerInstance(androidx.compose.ui.platform.LocalConfiguration.current.locales[0])
            if (summary.totalSeconds > 0 || summary.daysRead > 0) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.home_total_minutes, numbers.format(summary.totalSeconds / 60)), style = MaterialTheme.typography.titleLarge)
                        Text("${stringResource(R.string.reading_days)} · ${numbers.format(summary.daysRead)}")
                    }
                }
            }
            MeEntry(R.string.reading_notes, Icons.Default.NoteAlt, onNotes)
            MeEntry(R.string.vocabulary_title, Icons.Default.School, onVocabulary)
            MeEntry(R.string.reading_statistics, Icons.Default.BarChart, onStatistics)
            MeEntry(R.string.settings, Icons.Default.Settings, onSettings)
        }
    }
}

@Composable
private fun MeEntry(label: Int, icon: ImageVector, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        ListItem(headlineContent = { Text(stringResource(label)) },
            leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
            trailingContent = { Icon(Icons.Default.ChevronRight, null) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.heightIn(min = 64.dp))
    }
}
