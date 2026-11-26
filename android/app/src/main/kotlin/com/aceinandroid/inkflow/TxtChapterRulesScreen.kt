package com.aceinandroid.inkflow

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TxtChapterRulesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { TxtChapterRuleStore(context) }
    var revision by remember { mutableIntStateOf(0) }
    val custom = remember(revision) { store.customRules() }
    val selected = remember(revision) { store.activeRule().id }
    var editing by remember { mutableStateOf<TxtChapterRule?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<TxtChapterRule?>(null) }

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.txt_chapter_rules)) },
            navigationIcon = { IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
            } }, actions = { IconButton(onClick = { adding = true }) {
                Icon(Icons.Default.Add, stringResource(R.string.txt_add_rule))
            } })
    }) { insets ->
        Column(Modifier.padding(insets).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.txt_builtin_rules),
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                style = MaterialTheme.typography.titleSmall)
            TxtChapterRuleStore.builtins.forEach { rule ->
                RuleRow(rule, selected, false, onSelect = { store.select(rule.id); revision++ },
                    onEdit = {}, onDelete = {})
            }
            HorizontalDivider(Modifier.padding(top = 12.dp))
            Text(stringResource(R.string.txt_custom_rules),
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                style = MaterialTheme.typography.titleSmall)
            custom.forEach { rule ->
                RuleRow(rule, selected, true, onSelect = { store.select(rule.id); revision++ },
                    onEdit = { editing = rule }, onDelete = { deleting = rule })
            }
        }
    }
    if (adding || editing != null) {
        RuleEditor(editing, onDismiss = { adding = false; editing = null }, onSave = { rule ->
            store.saveCustom(rule)
            adding = false
            editing = null
            revision++
        })
    }
    deleting?.let { rule ->
        AlertDialog(onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.txt_delete_rule)) },
            text = { Text(rule.name) },
            confirmButton = { TextButton(onClick = {
                store.deleteCustom(rule.id)
                deleting = null
                revision++
            }) { Text(stringResource(R.string.txt_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) {
                Text(stringResource(R.string.cancel))
            } })
    }
}

@Composable
private fun RuleRow(rule: TxtChapterRule, selected: String, editable: Boolean,
                    onSelect: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val title = when (rule.id) {
        TxtChapterRuleStore.DEFAULT_ID -> stringResource(R.string.txt_rule_mixed)
        "cn_only_numeric" -> stringResource(R.string.txt_rule_chinese)
        "en_chapter_number" -> stringResource(R.string.txt_rule_english)
        "en_volume_number" -> stringResource(R.string.txt_rule_volumes)
        else -> rule.name
    }
    ListItem(headlineContent = { Text(title) }, supportingContent = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(rule.pattern, style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (selected == rule.id) rule.samples.take(3).forEach { sample ->
                val matched = runCatching { rule.expression().containsMatchIn(sample) }.getOrDefault(false)
                Text("${if (matched) stringResource(R.string.txt_match) else stringResource(R.string.txt_no_match)}: $sample",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (matched) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }, leadingContent = { RadioButton(selected = selected == rule.id, onClick = onSelect) },
        trailingContent = if (editable) { {
            Row {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, stringResource(R.string.txt_edit_rule))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, stringResource(R.string.txt_delete_rule))
                }
            }
        } } else null,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect))
}

@Composable
private fun RuleEditor(existing: TxtChapterRule?, onDismiss: () -> Unit,
                       onSave: (TxtChapterRule) -> Unit) {
    var name by remember(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var pattern by remember(existing?.id) { mutableStateOf(existing?.pattern.orEmpty()) }
    var samples by remember(existing?.id) { mutableStateOf(existing?.samples?.joinToString("\n").orEmpty()) }
    var caseSensitive by remember(existing?.id) { mutableStateOf(existing?.caseSensitive ?: false) }
    var multiLine by remember(existing?.id) { mutableStateOf(existing?.multiLine ?: true) }
    val id = remember(existing?.id) { existing?.id ?: TxtChapterRuleStore.newId() }
    val candidate = TxtChapterRule(id, name.trim(),
        pattern, samples.lines().map(String::trim).filter(String::isNotEmpty), caseSensitive, multiLine)
    val error = runCatching { TxtChapterRule.fromJson(candidate.toJson()) }.exceptionOrNull()
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(if (existing == null) R.string.txt_add_rule else R.string.txt_edit_rule)) },
        text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.txt_rule_name)) }, singleLine = true)
            OutlinedTextField(pattern, { pattern = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.txt_rule_pattern)) }, minLines = 2)
            OutlinedTextField(samples, { samples = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.txt_rule_samples)) }, minLines = 3)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.txt_case_sensitive))
                Switch(checked = caseSensitive, onCheckedChange = { caseSensitive = it })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.txt_multiline))
                Switch(checked = multiLine, onCheckedChange = { multiLine = it })
            }
            if (error != null && pattern.isNotBlank()) Text(error.message.orEmpty(),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            else if (error == null) candidate.samples.forEach { sample ->
                val matched = candidate.expression().containsMatchIn(sample)
                Text("${if (matched) stringResource(R.string.txt_match) else stringResource(R.string.txt_no_match)}: $sample",
                    style = MaterialTheme.typography.bodySmall)
            }
        } },
        confirmButton = { TextButton(enabled = error == null, onClick = { onSave(candidate) }) {
            Text(stringResource(R.string.save))
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
