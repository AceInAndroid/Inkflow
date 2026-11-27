package com.aceinandroid.inkflow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.aceinandroid.inkflow.ui.theme.AppAppearance
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aceinandroid.inkflow.localization.LanguageSettingsRow
import com.aceinandroid.inkflow.ai.NativeAiStore
import com.aceinandroid.inkflow.ui.ai.AiSettingsScreen
import com.aceinandroid.inkflow.core.ReaderEngineMode
import kotlin.math.roundToInt
import com.aceinandroid.inkflow.dictionary.DictionarySettingsPanel
import com.aceinandroid.inkflow.ui.settings.WirelessTransferScreen
import com.aceinandroid.inkflow.tts.TtsProviderSettings

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    store: BookStore,
    onSynced: () -> Unit,
    onBack: () -> Unit,
    restoring: Boolean,
    exportingBackup: Boolean,
    onRestore: () -> Unit,
    onExportBackup: () -> Unit,
    onWebDavDownloaded: () -> Unit,
    onEInkChanged: (Boolean) -> Unit = {},
    appAppearance: AppAppearance = AppAppearance.SYSTEM,
    onAppAppearanceChanged: (AppAppearance) -> Unit = {},
) {
    val context = LocalContext.current
    val appearanceStore = remember(context) { ReaderAppearanceStore(context) }
    val aiStore = remember(context) { NativeAiStore(context) }
    val narrator = remember(context) { NativeNarrator(context) }
    DisposableEffect(narrator) { onDispose { narrator.close() } }
    var appearance by remember { mutableStateOf(appearanceStore.load()) }
    var aiConfig by remember { mutableStateOf(aiStore.loadConfig()) }
    var appearanceDialog by rememberSaveable { mutableStateOf(false) }
    var aiConfigDialog by rememberSaveable { mutableStateOf(false) }
    var wirelessOpen by rememberSaveable { mutableStateOf(false) }
    var chapterRulesOpen by rememberSaveable { mutableStateOf(false) }
    var webDavBusy by remember { mutableStateOf(false) }
    BackHandler(enabled = webDavBusy) {}
    val controls = remember(context) { ReaderControlsStore(context) }
    var volumeKeysTurnPages by remember { mutableStateOf(controls.volumeKeysTurnPages()) }
    var eInkEnabled by remember { mutableStateOf(controls.eInkConfig().enabled) }
    var engineMode by remember { mutableStateOf(controls.readerEngineMode()) }

    if (wirelessOpen) {
        WirelessTransferScreen(store, onBack = { wirelessOpen = false }, onImported = onSynced)
        return
    }
    if (chapterRulesOpen) {
        TxtChapterRulesScreen(onBack = { chapterRulesOpen = false })
        return
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.settings)) }, navigationIcon = {
            IconButton(onClick = onBack, enabled = !webDavBusy) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
            }
        })
    }) { insets ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                LanguageSettingsRow()
            }
            Card(shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.home_appearance), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.home_appearance_summary), style = MaterialTheme.typography.bodySmall)
                    AppAppearance.entries.forEach { mode ->
                        FilterChip(selected = mode == appAppearance,
                            onClick = { onAppAppearanceChanged(mode) },
                            label = { Text(stringResource(when (mode) {
                                AppAppearance.SYSTEM -> R.string.home_theme_system
                                AppAppearance.LIGHT -> R.string.home_theme_light
                                AppAppearance.DARK -> R.string.home_theme_dark
                            })) })
                    }
                    if (eInkEnabled) Text(stringResource(R.string.home_hardware_eink), style = MaterialTheme.typography.bodySmall)
                }
            }
            Card(shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        stringResource(R.string.reading_settings),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.reader_appearance)) },
                        supportingContent = {
                            Text("${appearance.fontSize} sp, ${"%.1f".format(appearance.lineSpacing)}")
                        },
                        leadingContent = { Icon(Icons.Default.Tune, null) },
                        trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                        modifier = Modifier.fillMaxWidth().clickable { appearanceDialog = true },
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.reader_engine), style = MaterialTheme.typography.labelLarge)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = engineMode == ReaderEngineMode.NATIVE_PAGED,
                                onClick = {
                                    engineMode = ReaderEngineMode.NATIVE_PAGED
                                    controls.setReaderEngineMode(engineMode)
                                },
                                label = { Text(stringResource(R.string.engine_native_paged)) },
                            )
                            FilterChip(
                                selected = engineMode == ReaderEngineMode.NATIVE_SCROLL,
                                onClick = {
                                    engineMode = ReaderEngineMode.NATIVE_SCROLL
                                    controls.setReaderEngineMode(engineMode)
                                },
                                label = { Text(stringResource(R.string.engine_native_scroll)) },
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.eink_mode))
                            Text(
                                stringResource(R.string.eink_mode_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = eInkEnabled, onCheckedChange = {
                            eInkEnabled = it
                            controls.setEInkEnabled(it)
                            onEInkChanged(it)
                        })
                    }
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.txt_chapter_rules)) },
                        supportingContent = { Text(stringResource(R.string.txt_chapter_rules_summary)) },
                        leadingContent = { Icon(Icons.Default.FormatListNumbered, null) },
                        trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                        modifier = Modifier.fillMaxWidth().clickable { chapterRulesOpen = true },
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.volume_key_turn_page))
                        Switch(checked = volumeKeysTurnPages, onCheckedChange = {
                            controls.setVolumeKeysTurnPages(it)
                            volumeKeysTurnPages = it
                        })
                    }
                    HorizontalDivider()
                    Text(
                        stringResource(R.string.ai_assistant),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.ai_settings)) },
                        supportingContent = {
                            Text(
                                if (aiConfig.isConfigured) "${aiConfig.providerType.displayName} · ${aiConfig.model}"
                                else stringResource(R.string.ai_settings_summary),
                            )
                        },
                        leadingContent = { Icon(Icons.Default.AutoAwesome, null) },
                        trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                        modifier = Modifier.fillMaxWidth().clickable { aiConfigDialog = true },
                    )
                    HorizontalDivider()
                    Text(
                        stringResource(R.string.narration_settings),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text("${stringResource(R.string.speech_rate)}: ${"%.1f".format(narrator.rate)}x")
                    Slider(value = narrator.rate, onValueChange = { value ->
                        narrator.updateRate((value * 10).roundToInt() / 10f)
                    }, valueRange = 0.1f..4f, steps = 38)
                    Text("${stringResource(R.string.speech_pitch)}: ${"%.1f".format(narrator.pitch)}x")
                    Slider(value = narrator.pitch, onValueChange = { value ->
                        narrator.updatePitch((value * 10).roundToInt() / 10f)
                    }, valueRange = 0.5f..2f, steps = 14)
                    SpeechVoiceSelector(narrator.voices, narrator.selectedVoiceName,
                        onSelect = { narrator.updateVoice(it) }, enabled = !narrator.onlineEnabled)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.continuous_narration))
                        Switch(checked = narrator.continuous, onCheckedChange = narrator::updateContinuous)
                    }
                    HorizontalDivider()
                    TtsProviderSettings(narrator)
                    HorizontalDivider()
                    DictionarySettingsPanel()
                    HorizontalDivider()
                    ProgressSyncSettings(store, onSynced)
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.p2_wireless_transfer)) },
                        trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !restoring && !webDavBusy) { wirelessOpen = true },
                    )
                    WebDavSettings(store, onWebDavDownloaded, onBusyChange = { webDavBusy = it }, enabled = !restoring && !exportingBackup)
                    HorizontalDivider()
                    Text(
                        stringResource(R.string.storage_settings),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.export_backup)) },
                        supportingContent = { Text(stringResource(R.string.export_backup_summary)) },
                        leadingContent = { Icon(Icons.Default.SaveAlt, null) },
                        trailingContent = {
                            if (exportingBackup) CircularProgressIndicator(Modifier.size(24.dp))
                            else Icon(Icons.Default.ChevronRight, null)
                        },
                        modifier = Modifier.fillMaxWidth()
                            .clickable(enabled = !restoring && !exportingBackup && !webDavBusy, onClick = onExportBackup),
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.restore_backup)) },
                        supportingContent = { Text(stringResource(R.string.restore_backup_summary)) },
                        leadingContent = { Icon(Icons.Default.Restore, null) },
                        trailingContent = {
                            if (restoring) CircularProgressIndicator(Modifier.size(24.dp))
                            else Icon(Icons.Default.ChevronRight, null)
                        },
                        modifier = Modifier.fillMaxWidth()
                            .clickable(enabled = !restoring && !exportingBackup && !webDavBusy, onClick = onRestore),
                    )
                }
            }
        }
    }
    if (appearanceDialog) {
        ReaderAppearanceDialog(appearance, onChange = { updated ->
            appearance = updated
            appearanceStore.save(updated)
        }, onDismiss = { appearanceDialog = false })
    }
    if (aiConfigDialog) {
        AiSettingsScreen(onDismiss = { aiConfig = aiStore.loadConfig(); aiConfigDialog = false })
    }
}
