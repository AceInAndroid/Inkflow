package com.aceinandroid.inkflow.ui.home

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aceinandroid.inkflow.R
import com.aceinandroid.inkflow.ReadingSummary
import com.aceinandroid.inkflow.StoredBook
import com.aceinandroid.inkflow.ui.theme.InkflowTheme
import com.aceinandroid.inkflow.ui.theme.LocalEInkConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

@Composable
fun HomeScreen(
    books: List<StoredBook>, summary: ReadingSummary, loading: Boolean,
    snackbar: SnackbarHostState, importing: Boolean, onOpen: (StoredBook) -> Unit,
    onLibrary: () -> Unit, onImport: () -> Unit, onStatistics: () -> Unit,
    onWifiTransfer: () -> Unit = onImport,
) {
    val context = LocalContext.current
    val store = remember(context) { HomeStore(context) }
    var style by remember { mutableStateOf(store.style()) }
    var motto by remember { mutableStateOf(store.motto()) }
    var styleDialog by rememberSaveable { mutableStateOf(false) }
    var mottoDialog by rememberSaveable { mutableStateOf(false) }
    var mottoDraft by rememberSaveable { mutableStateOf("") }
    var wallpaperVersion by remember { mutableIntStateOf(0) }
    var wallpaperBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val hardware = LocalEInkConfig.current
    val monochrome = hardware.enabled || style == HomeStyle.EINK
    val wallpaper by produceState<Bitmap?>(null, style, monochrome, wallpaperVersion) {
        value = if (style == HomeStyle.IMMERSIVE && !monochrome)
            withContext(Dispatchers.IO) { store.loadWallpaper() } else null
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            wallpaperBusy = true
            try {
                withContext(Dispatchers.IO) { store.importWallpaper(uri) }
                wallpaperVersion++
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { snackbar.showSnackbar(context.getString(R.string.home_wallpaper_error)) }
            finally { wallpaperBusy = false }
        }
    }
    val content: @Composable () -> Unit = {
        HomeBody(books, summary, loading, style, monochrome, wallpaper, motto, snackbar, importing,
            onOpen, onLibrary, onImport, onStatistics, onWifiTransfer,
            onStyle = { styleDialog = true },
            onMotto = { mottoDraft = motto; mottoDialog = true })
        if (styleDialog) AlertDialog(
            onDismissRequest = { if (!wallpaperBusy) styleDialog = false },
            title = { Text(stringResource(R.string.home_style)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HomeStyle.entries.forEach { option ->
                        Surface(onClick = { style = option; store.setStyle(option) },
                            shape = RoundedCornerShape(12.dp),
                            color = if (option == style) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = option == style, onClick = null)
                                Text(stringResource(option.label()), Modifier.padding(8.dp))
                            }
                        }
                    }
                    if (hardware.enabled) Text(stringResource(R.string.home_hardware_eink), style = MaterialTheme.typography.bodySmall)
                    if (style == HomeStyle.IMMERSIVE && !hardware.enabled) {
                        TextButton(enabled = !wallpaperBusy, onClick = { picker.launch(arrayOf("image/*")) }) {
                            Text(stringResource(if (wallpaperBusy) R.string.home_wallpaper_loading else R.string.home_wallpaper_choose))
                        }
                        TextButton(enabled = !wallpaperBusy && wallpaper != null, onClick = { store.removeWallpaper(); wallpaperVersion++ }) {
                            Text(stringResource(R.string.home_wallpaper_remove))
                        }
                    }
                }
            },
            confirmButton = { TextButton(enabled = !wallpaperBusy, onClick = { styleDialog = false }) { Text(stringResource(R.string.save)) } },
        )
        if (mottoDialog) AlertDialog(
            onDismissRequest = { mottoDialog = false },
            title = { Text(stringResource(R.string.home_edit_motto)) },
            text = { OutlinedTextField(value = mottoDraft, onValueChange = { mottoDraft = it.take(240) },
                label = { Text(stringResource(R.string.home_motto_hint)) }, maxLines = 5) },
            confirmButton = { TextButton(onClick = { store.setMotto(mottoDraft); motto = store.motto(); mottoDialog = false }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { mottoDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    // A home style affects only this subtree; hardware mode also applies to the rest of the app.
    if (monochrome) InkflowTheme(eInkConfig = hardware.copy(enabled = true), content = content) else content()
}

private fun HomeStyle.label(): Int = when (this) {
    HomeStyle.CLASSIC -> R.string.home_classic
    HomeStyle.IMMERSIVE -> R.string.home_immersive
    HomeStyle.MINIMAL -> R.string.home_minimal
    HomeStyle.EINK -> R.string.home_eink
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeBody(
    books: List<StoredBook>, summary: ReadingSummary, loading: Boolean, style: HomeStyle,
    monochrome: Boolean, wallpaper: Bitmap?, motto: String, snackbar: SnackbarHostState,
    importing: Boolean, onOpen: (StoredBook) -> Unit, onLibrary: () -> Unit,
    onImport: () -> Unit, onStatistics: () -> Unit, onWifiTransfer: () -> Unit, onStyle: () -> Unit, onMotto: () -> Unit,
) {
    val immersive = style == HomeStyle.IMMERSIVE && !monochrome
    val minimal = style == HomeStyle.MINIMAL && !monochrome
    val locale = LocalConfiguration.current.locales[0]
    val numbers = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    val recent = remember(books, summary) { recentHomeBooks(books, summary) }
    val next = recent.firstOrNull() ?: books.maxByOrNull { it.id }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (immersive && wallpaper != null) {
            Image(wallpaper.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .55f)))
        }
        Scaffold(containerColor = Color.Transparent, snackbarHost = { SnackbarHost(snackbar) },
            topBar = { TopAppBar(title = { Text(stringResource(R.string.home_tab)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent,
                    titleContentColor = if (immersive && wallpaper != null) Color.White else MaterialTheme.colorScheme.onBackground),
                actions = { IconButton(onClick = onStyle) { Icon(Icons.Default.Tune, stringResource(R.string.home_style),
                    tint = if (immersive && wallpaper != null) Color.White else MaterialTheme.colorScheme.primary) } }) },
        ) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(if (minimal) 12.dp else 20.dp)) {
                item {
                    Column(Modifier.padding(top = if (immersive) 64.dp else 4.dp, bottom = if (immersive) 36.dp else 0.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val foreground = if (immersive && wallpaper != null) Color.White else MaterialTheme.colorScheme.onBackground
                        Text(DateFormat.getDateInstance(DateFormat.FULL, locale).format(Date()), color = foreground,
                            style = MaterialTheme.typography.labelLarge)
                        Text(stringResource(R.string.home_greeting), color = foreground,
                            style = if (minimal) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold)
                        Surface(onClick = onMotto, color = Color.Transparent,
                            contentColor = foreground, shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.padding(vertical = 12.dp)) {
                                Text(motto.ifBlank { stringResource(R.string.home_motto_default) }, style = MaterialTheme.typography.bodyLarge)
                                Text(stringResource(R.string.home_edit_motto), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (immersive && wallpaper == null) Text(stringResource(R.string.home_wallpaper_hint),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
                item {
                    HomePanel(minimal, monochrome, onStatistics) {
                        Text(stringResource(R.string.home_statistics), style = MaterialTheme.typography.titleMedium)
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            HomeMetric(numbers.format(books.size), stringResource(R.string.home_all_books), Modifier.weight(1f))
                            HomeMetric(stringResource(R.string.home_total_minutes, numbers.format(summary.totalSeconds / 60)),
                                stringResource(R.string.total_reading_time), Modifier.weight(1f))
                            HomeMetric(numbers.format(summary.daysRead), stringResource(R.string.reading_days), Modifier.weight(1f))
                        }
                    }
                }
                item {
                    if (next == null) HomePanel(minimal, monochrome, onImport) {
                        Text(stringResource(R.string.empty_library), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.home_no_recent))
                        Button(onClick = onImport, enabled = !importing) { Text(stringResource(R.string.import_book)) }
                        OutlinedButton(onClick = onWifiTransfer, enabled = !importing) { Text(stringResource(R.string.p2_wireless_transfer)) }
                    } else HomePanel(minimal, monochrome, { onOpen(next) }) {
                        Text(stringResource(if (recent.isEmpty()) R.string.home_browse else R.string.home_continue),
                            color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        HomeBook(next, covers = !minimal && !monochrome)
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.home_recent), style = MaterialTheme.typography.titleMedium,
                            color = if (immersive && wallpaper != null) Color.White else MaterialTheme.colorScheme.onBackground)
                        TextButton(onClick = onLibrary, colors = ButtonDefaults.textButtonColors(
                            contentColor = if (immersive && wallpaper != null) Color.White else MaterialTheme.colorScheme.primary)) {
                            Text(stringResource(R.string.home_browse))
                        }
                    }
                }
                if (recent.isEmpty()) item {
                    HomePanel(minimal, monochrome, onLibrary) { Text(stringResource(R.string.home_no_recent)) }
                }
                items(recent.take(6), key = { it.id }) { book ->
                    HomePanel(minimal, monochrome, { onOpen(book) }) { HomeBook(book, covers = !minimal && !monochrome) }
                }
            }
        }
    }
}

@Composable
private fun HomePanel(minimal: Boolean, monochrome: Boolean, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(if (minimal) 0.dp else 16.dp),
        color = if (minimal) Color.Transparent else MaterialTheme.colorScheme.surface,
        border = if (monochrome) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null) {
        Column(Modifier.padding(if (minimal) 4.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun HomeMetric(value: String, label: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HomeBook(book: StoredBook, covers: Boolean) {
    val locale = LocalConfiguration.current.locales[0]
    val percentage = NumberFormat.getPercentInstance(locale).format(book.progress.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (covers) HomeCover(book)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (book.author.isNotBlank()) Text(book.author, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.home_progress, percentage), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun HomeCover(book: StoredBook) {
    val bitmap by produceState<Bitmap?>(null, book.cover?.path) {
        value = withContext(Dispatchers.IO) {
            try {
                book.cover?.let { file ->
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.path, bounds)
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = 1
                        while (bounds.outWidth / inSampleSize > 240 || bounds.outHeight / inSampleSize > 320) inSampleSize *= 2
                    }
                    BitmapFactory.decodeFile(file.path, options)
                }
            } catch (_: Exception) { null }
        }
    }
    Surface(Modifier.size(56.dp, 80.dp), shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        val image = bitmap
        if (image != null) Image(image.asImageBitmap(), null, contentScale = ContentScale.Crop)
        else Box(contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Filled.MenuBook, null) }
    }
}
