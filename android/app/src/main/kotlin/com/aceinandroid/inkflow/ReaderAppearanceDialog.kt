package com.aceinandroid.inkflow

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderAppearanceDialog(
    appearance: ReaderAppearance,
    onChange: (ReaderAppearance) -> Unit,
    onDismiss: () -> Unit,
    swipeToTurnPage: Boolean = false,
    onSwipeToTurnPageChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val store = remember(context) { ReaderAppearanceStore(context) }
    val scope = rememberCoroutineScope()
    var fonts by remember { mutableStateOf(store.fonts()) }
    var images by remember { mutableStateOf(store.images()) }
    var error by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<ImportedFont?>(null) }
    var removingImage by remember { mutableStateOf<ReaderBackgroundImage?>(null) }
    var backgroundHex by remember { mutableStateOf(appearance.customBackground ?: "FFFBFBF3") }
    var foregroundHex by remember { mutableStateOf(appearance.customForeground ?: "FF343434") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            error = null
            try {
                val imported = withContext(Dispatchers.IO) { store.importFont(uri) }
                fonts = store.fonts()
                onChange(appearance.copy(customFontId = imported.id))
            } catch (failure: Exception) {
                error = failure.message ?: context.getString(R.string.font_import_failed)
            } finally {
                importing = false
            }
        }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            error = null
            try {
                val id = withContext(Dispatchers.IO) { store.importImage(uri) }
                images = store.images()
                onChange(appearance.copy(backgroundImage = ReaderBackgroundImage("localFile", id)))
            } catch (failure: Exception) {
                error = failure.message ?: context.getString(R.string.image_import_failed)
            } finally { importing = false }
        }
    }
    val nightPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && appearance.backgroundImage != null) scope.launch {
            importing = true
            error = null
            try {
                val id = withContext(Dispatchers.IO) {
                    store.importNightImage(appearance.backgroundImage.path, uri)
                }
                images = store.images()
                onChange(appearance.copy(backgroundImage = appearance.backgroundImage.copy(nightPath = id)))
            } catch (failure: Exception) {
                error = failure.message ?: context.getString(R.string.image_import_failed)
            } finally { importing = false }
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        scrimColor = androidx.compose.ui.graphics.Color.Transparent,
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.reader_appearance), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.migration_page_margin, appearance.pageMargin))
                Slider(value = appearance.pageMargin.toFloat(), onValueChange = {
                    onChange(appearance.copy(pageMargin = it.roundToInt()))
                }, valueRange = 4f..48f, steps = 43)
                Text(stringResource(R.string.migration_paragraph_spacing, appearance.paragraphSpacing))
                Slider(value = appearance.paragraphSpacing.toFloat(), onValueChange = {
                    onChange(appearance.copy(paragraphSpacing = it.roundToInt()))
                }, valueRange = 0f..32f, steps = 31)
                Text(stringResource(R.string.migration_indent, appearance.firstLineIndent))
                Slider(value = appearance.firstLineIndent.toFloat(), onValueChange = {
                    onChange(appearance.copy(firstLineIndent = it.roundToInt()))
                }, valueRange = 0f..64f, steps = 63)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReaderAlignment.entries.forEach { align ->
                        FilterChip(selected = appearance.alignment == align,
                            onClick = { onChange(appearance.copy(alignment = align)) },
                            label = { Text(stringResource(when (align) {
                                ReaderAlignment.BOOK -> R.string.migration_align_book
                                ReaderAlignment.START -> R.string.migration_align_start
                                ReaderAlignment.JUSTIFY -> R.string.migration_align_justify
                                ReaderAlignment.CENTER -> R.string.migration_align_center
                            })) })
                    }
                }
                ReaderAppearanceToggle(stringResource(R.string.migration_header), appearance.showHeader) {
                    onChange(appearance.copy(showHeader = it))
                }
                ReaderAppearanceToggle(stringResource(R.string.migration_footer), appearance.showFooter) {
                    onChange(appearance.copy(showFooter = it))
                }
                ReaderAppearanceToggle(stringResource(R.string.migration_time), appearance.showTime) {
                    onChange(appearance.copy(showTime = it))
                }
                ReaderAppearanceToggle(stringResource(R.string.migration_battery), appearance.showBattery) {
                    onChange(appearance.copy(showBattery = it))
                }
                ReaderAppearanceToggle(stringResource(R.string.migration_swipe_paging), swipeToTurnPage,
                    onSwipeToTurnPageChange)
                Text("${stringResource(R.string.font_size)}: ${appearance.fontSize}")
                Slider(value = appearance.fontSize.toFloat(), onValueChange = {
                    onChange(appearance.copy(fontSize = it.roundToInt()))
                }, valueRange = 14f..28f, steps = 13)
                Text("${stringResource(R.string.line_spacing)}: ${"%.1f".format(appearance.lineSpacing)}")
                Slider(value = appearance.lineSpacing, onValueChange = {
                    onChange(appearance.copy(lineSpacing = (it * 10).roundToInt() / 10f))
                }, valueRange = 1.2f..2.2f, steps = 9)
                Text(stringResource(R.string.font_family))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReaderFont.entries.forEach { font ->
                        val label = when (font) {
                            ReaderFont.SYSTEM -> R.string.font_system
                            ReaderFont.SERIF -> R.string.font_serif
                            ReaderFont.MONOSPACE -> R.string.font_monospace
                        }
                        FilterChip(selected = appearance.customFontId == null && appearance.font == font,
                            onClick = { onChange(appearance.copy(font = font, customFontId = null)) },
                            label = { Text(stringResource(label)) })
                    }
                }
                fonts.forEach { font ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = appearance.customFontId == font.id,
                            onClick = { onChange(appearance.copy(customFontId = font.id)) },
                            label = { Text(font.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.weight(1f))
                        IconButton(onClick = { removing = font }) {
                            Icon(Icons.Default.Delete, stringResource(R.string.remove_font))
                        }
                    }
                }
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !importing) {
                    Icon(Icons.Default.FileUpload, null)
                    Text(stringResource(if (importing) R.string.font_importing else R.string.import_font))
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(stringResource(R.string.reader_theme))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ReaderPalette.entries.forEach { palette ->
                        val label = when (palette) {
                            ReaderPalette.LIGHT -> R.string.theme_light
                            ReaderPalette.WARM -> R.string.theme_warm
                            ReaderPalette.DARK -> R.string.theme_dark
                            ReaderPalette.CUSTOM -> R.string.theme_custom
                        }
                        val name = stringResource(label)
                        val background = if (palette == ReaderPalette.CUSTOM)
                            readerThemeColor(backgroundHex) ?: ReaderPalette.LIGHT.background
                            else palette.background
                        val foreground = if (palette == ReaderPalette.CUSTOM)
                            readerThemeColor(foregroundHex) ?: ReaderPalette.LIGHT.foreground
                            else palette.foreground
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(48.dp)
                                .background(background)
                                .border(if (appearance.palette == palette) 3.dp else 1.dp,
                                    if (appearance.palette == palette) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline)
                                .clickable { onChange(appearance.copy(palette = palette,
                                    customBackground = if (palette == ReaderPalette.CUSTOM)
                                        backgroundHex.takeIf { readerThemeColor(it) != null }
                                            ?: appearance.customBackground ?: "FFFBFBF3"
                                        else appearance.customBackground,
                                    customForeground = if (palette == ReaderPalette.CUSTOM)
                                        foregroundHex.takeIf { readerThemeColor(it) != null }
                                            ?: appearance.customForeground ?: "FF343434"
                                        else appearance.customForeground)) }
                                .semantics { contentDescription = name; selected = appearance.palette == palette },
                                contentAlignment = Alignment.Center) {
                                Text("A", color = foreground)
                            }
                            Text(name, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                if (appearance.palette == ReaderPalette.CUSTOM) {
                    OutlinedTextField(value = backgroundHex, onValueChange = { raw ->
                        val next = raw.removePrefix("#").uppercase().take(8)
                        if (next.all { it in '0'..'9' || it in 'A'..'F' }) {
                            backgroundHex = next
                            if (next.length == 8) onChange(appearance.copy(customBackground = next))
                        }
                    }, label = { Text(stringResource(R.string.theme_background_color)) },
                        placeholder = { Text("AARRGGBB") }, singleLine = true,
                        isError = backgroundHex.length != 8, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = foregroundHex, onValueChange = { raw ->
                        val next = raw.removePrefix("#").uppercase().take(8)
                        if (next.all { it in '0'..'9' || it in 'A'..'F' }) {
                            foregroundHex = next
                            if (next.length == 8) onChange(appearance.copy(customForeground = next))
                        }
                    }, label = { Text(stringResource(R.string.theme_text_color)) },
                        placeholder = { Text("AARRGGBB") }, singleLine = true,
                        isError = foregroundHex.length != 8, modifier = Modifier.fillMaxWidth())
                }
                Text(stringResource(R.string.reader_background_image))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = appearance.backgroundImage == null,
                        onClick = { onChange(appearance.copy(backgroundImage = null)) },
                        label = { Text(stringResource(R.string.background_none)) })
                    (1..3).forEach { number ->
                        val day = "assets/images/bgimg/bg$number.jpg"
                        val night = "assets/images/bgimg/bg${7 - number}.jpg"
                        FilterChip(selected = appearance.backgroundImage?.path == day,
                            onClick = { onChange(appearance.copy(backgroundImage =
                                ReaderBackgroundImage("assets", day, night,
                                    alignment = if (number == 1) "bottom" else "center"))) },
                            label = { Text("$number") })
                    }
                }
                images.forEach { name ->
                    FilterChip(selected = appearance.backgroundImage?.path == name,
                        onClick = { onChange(appearance.copy(backgroundImage =
                            store.backgroundForLocal(name))) },
                        label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.fillMaxWidth())
                }
                OutlinedButton(onClick = { imagePicker.launch(arrayOf("image/*")) }, enabled = !importing) {
                    Icon(Icons.Default.FileUpload, null)
                    Text(stringResource(R.string.import_background_image))
                }
                appearance.backgroundImage?.let { image ->
                    if (image.type == "localFile") {
                        OutlinedButton(onClick = { nightPicker.launch(arrayOf("image/*")) }, enabled = !importing) {
                            Text(stringResource(R.string.import_night_image))
                        }
                        OutlinedButton(onClick = { removingImage = image }) {
                            Icon(Icons.Default.Delete, null)
                            Text(stringResource(R.string.remove_background_image))
                        }
                    }
                    if (image.nightPath != null) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.background_auto_adjust))
                            Switch(checked = image.autoAdjust, onCheckedChange = {
                                onChange(appearance.copy(backgroundImage = image.copy(autoAdjust = it)))
                            })
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("day", "night").forEach { mode ->
                                FilterChip(selected = (image.selectedMode ?: "day") == mode,
                                    onClick = { onChange(appearance.copy(backgroundImage = image.copy(selectedMode = mode))) },
                                    enabled = !image.autoAdjust,
                                    label = { Text(stringResource(if (mode == "day") R.string.background_day else R.string.background_night)) })
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("cover", "stretch").forEach { fit ->
                            FilterChip(selected = image.fit == fit,
                                onClick = { onChange(appearance.copy(backgroundImage = image.copy(fit = fit))) },
                                label = { Text(stringResource(if (fit == "cover") R.string.background_cover else R.string.background_stretch)) })
                        }
                    }
                    Text("${stringResource(R.string.background_opacity)}: ${(image.opacity * 100).roundToInt()}%")
                    Slider(value = image.opacity.coerceIn(0.1f, 1f), onValueChange = {
                        onChange(appearance.copy(backgroundImage = image.copy(opacity =
                            (it * 20).roundToInt() / 20f)))
                    }, valueRange = 0.1f..1f, steps = 17)
                    Text("${stringResource(R.string.background_blur)}: ${"%.1f".format(image.blur)}")
                    Slider(value = image.blur, onValueChange = {
                        onChange(appearance.copy(backgroundImage = image.copy(blur =
                            (it * 2).roundToInt() / 2f)))
                    }, valueRange = 0f..20f, steps = 39)
                }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        }
    }
    removing?.let { font ->
        AlertDialog(onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.remove_font)) },
            text = { Text(stringResource(R.string.remove_font_confirm, font.name)) },
            confirmButton = { TextButton(onClick = {
                removing = null
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { store.removeFont(font.id) }
                        fonts = store.fonts()
                        if (appearance.customFontId == font.id) onChange(appearance.copy(customFontId = null))
                    } catch (failure: Exception) {
                        error = failure.message ?: context.getString(R.string.font_remove_failed)
                    }
                }
            }) { Text(stringResource(R.string.remove_font)) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } })
    }
    removingImage?.let { image ->
        AlertDialog(onDismissRequest = { removingImage = null },
            title = { Text(stringResource(R.string.remove_background_image)) },
            text = { Text(stringResource(R.string.remove_background_image_confirm)) },
            confirmButton = { TextButton(onClick = {
                removingImage = null
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { store.removeImage(image) }
                        images = store.images()
                        onChange(appearance.copy(backgroundImage = null))
                    } catch (failure: Exception) {
                        error = failure.message ?: context.getString(R.string.image_import_failed)
                    }
                }
            }) { Text(stringResource(R.string.remove_background_image)) } },
            dismissButton = { TextButton(onClick = { removingImage = null }) {
                Text(stringResource(R.string.cancel))
            } })
    }
}

@Composable
private fun ReaderAppearanceToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
