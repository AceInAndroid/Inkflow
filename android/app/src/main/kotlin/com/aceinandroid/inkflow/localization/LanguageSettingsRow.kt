package com.aceinandroid.inkflow.localization

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aceinandroid.inkflow.R
import com.aceinandroid.inkflow.core.localization.AppLanguage

@Composable
fun LanguageSettingsRow() {
    var open by rememberSaveable { mutableStateOf(false) }
    val current = NativeLocaleController.resolution.language.nativeName
    ListItem(
        headlineContent = { Text(stringResource(R.string.app_language)) },
        supportingContent = { Text(if (NativeLocaleController.automatic)
            stringResource(R.string.language_auto_summary, current) else current) },
        leadingContent = { Icon(Icons.Default.Language, null) },
        trailingContent = { Icon(Icons.Default.ChevronRight, null) },
        modifier = Modifier.fillMaxWidth().clickable { open = true },
    )
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.language_choose)) },
        text = {
            Column {
                val options = listOf<Pair<AppLanguage?, String>>(null to stringResource(R.string.language_auto)) +
                    listOf(AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.ENGLISH,
                        AppLanguage.JAPANESE, AppLanguage.KOREAN).map { it to it.nativeName }
                options.forEach { (language, name) ->
                    val selected = if (language == null) NativeLocaleController.automatic
                        else NativeLocaleController.choice == language.tag
                    Row(Modifier.fillMaxWidth().clickable {
                        open = false
                        NativeLocaleController.select(language)
                    }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected, onClick = null)
                        Text(name, Modifier.padding(start = 8.dp).weight(1f))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
    )
}
