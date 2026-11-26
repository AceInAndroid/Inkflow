package com.aceinandroid.inkflow

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.aceinandroid.inkflow.core.readingHeatmap
import java.util.Calendar
import java.text.DateFormatSymbols
import java.util.Locale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingStatisticsScreen(
    summary: ReadingSummary,
    loading: Boolean,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
) {
    var period by remember { mutableStateOf(ReadingPeriod.WEEK) }
    var offset by remember { mutableIntStateOf(0) }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    val periodData = remember(summary, period, offset, selectedKey) {
        readingPeriodData(summary, period, offset, selectedKey)
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        TopAppBar(title = { Text(stringResource(R.string.reading_statistics)) }, navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
            }
        })
    }) { insets ->
        if (loading) Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        } else Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatisticValue(stringResource(R.string.total_reading_time),
                    durationText(summary.totalSeconds), Modifier.weight(1.4f))
                StatisticValue(stringResource(R.string.reading_days),
                    summary.daysRead.toString(), Modifier.weight(1f))
                StatisticValue(stringResource(R.string.books_read),
                    summary.booksRead.toString(), Modifier.weight(1f))
            }
            HorizontalDivider()
            YearlyReadingHeatmap(summary)
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReadingPeriod.entries.forEach { option ->
                    val label = when (option) {
                        ReadingPeriod.WEEK -> stringResource(R.string.period_week)
                        ReadingPeriod.MONTH -> stringResource(R.string.period_month)
                        ReadingPeriod.YEAR -> stringResource(R.string.period_year)
                        ReadingPeriod.ALL -> stringResource(R.string.period_all)
                    }
                    FilterChip(selected = period == option, onClick = {
                        period = option
                        offset = 0
                        selectedKey = null
                    }, label = { Text(label) })
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (period != ReadingPeriod.ALL) IconButton(onClick = {
                    offset--
                    selectedKey = null
                }) { Icon(Icons.Default.ChevronLeft, stringResource(R.string.previous_period)) }
                Text(periodData.title ?: stringResource(R.string.period_all),
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                if (period != ReadingPeriod.ALL) IconButton(onClick = {
                    offset++
                    selectedKey = null
                }, enabled = offset < 0) {
                    Icon(Icons.Default.ChevronRight, stringResource(R.string.next_period))
                }
            }
            Text(stringResource(R.string.period_reading_time, durationText(periodData.seconds)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            val maximum = periodData.buckets.maxOfOrNull(ReadingBucket::seconds)?.coerceAtLeast(1) ?: 1
            if (period == ReadingPeriod.MONTH) {
                val weekdays = listOf(R.string.weekday_mon, R.string.weekday_tue, R.string.weekday_wed,
                    R.string.weekday_thu, R.string.weekday_fri, R.string.weekday_sat, R.string.weekday_sun)
                Row(Modifier.fillMaxWidth()) {
                    weekdays.forEach { day ->
                        Text(stringResource(day), Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val cells = buildList<ReadingBucket?> {
                    repeat(periodData.leadingEmptySlots) { add(null) }
                    addAll(periodData.buckets)
                    while (size % 7 != 0) add(null)
                }
                cells.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth()) {
                        week.forEach { bucket ->
                            if (bucket == null) Box(Modifier.weight(1f).height(56.dp))
                            else Column(Modifier.weight(1f).height(56.dp).clickable {
                                selectedKey = if (selectedKey == bucket.key) null else bucket.key
                            }.background(if (periodData.selectedKey == bucket.key)
                                MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                                .padding(6.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                Text(bucket.label, style = MaterialTheme.typography.bodySmall)
                                Box(Modifier.fillMaxWidth().height(4.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)) {
                                    if (bucket.seconds > 0) Box(Modifier
                                        .fillMaxWidth(bucket.seconds.toFloat() / maximum).height(4.dp)
                                        .background(MaterialTheme.colorScheme.primary))
                                }
                            }
                        }
                    }
                }
            } else periodData.buckets.forEach { bucket ->
                Row(Modifier.fillMaxWidth().clickable {
                    selectedKey = if (selectedKey == bucket.key) null else bucket.key
                }.background(if (periodData.selectedKey == bucket.key)
                    MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(bucket.label, style = MaterialTheme.typography.bodySmall)
                    Box(Modifier.weight(1f).height(8.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant)) {
                        if (bucket.seconds > 0) Box(Modifier.fillMaxWidth(bucket.seconds.toFloat() / maximum)
                            .height(8.dp).background(MaterialTheme.colorScheme.primary))
                    }
                    Text(durationText(bucket.seconds), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (periodData.buckets.isEmpty()) Text(stringResource(R.string.no_reading_history),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            Text(if (periodData.selectedKey == null) stringResource(R.string.books_by_reading_time)
                else stringResource(R.string.books_for_period, periodData.selectedKey),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (periodData.books.isEmpty()) Text(stringResource(R.string.no_reading_history),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            else periodData.books.forEach { book ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(book.title?.ifBlank { null } ?: stringResource(R.string.unknown_book, book.id),
                        modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(durationText(book.seconds), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun StatisticValue(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun durationText(seconds: Int): String = when {
    seconds >= 3600 -> stringResource(R.string.duration_hours_minutes, seconds / 3600, seconds % 3600 / 60)
    seconds >= 60 -> stringResource(R.string.duration_minutes_seconds, seconds / 60, seconds % 60)
    else -> stringResource(R.string.duration_seconds, seconds)
}

@Composable
private fun YearlyReadingHeatmap(summary: ReadingSummary) {
    val today = Calendar.getInstance()
    val currentYear = today.get(Calendar.YEAR)
    var year by remember { mutableIntStateOf(currentYear) }
    var selectedDate by remember { mutableStateOf<String?>(null) }
    val todayKey = "${currentYear}-${today.get(Calendar.MONTH)}-${today.get(Calendar.DAY_OF_MONTH)}"
    val data = remember(summary, year, todayKey) {
        val seconds = if (summary.history.isNotEmpty()) summary.history.groupBy { it.date }
            .mapValues { (_, records) -> records.sumOf { it.seconds.coerceAtLeast(0).toLong() }
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
            else summary.recentDays.associate { it.date to it.seconds }
        readingHeatmap(year, seconds, today)
    }
    val colors = listOf(MaterialTheme.colorScheme.surfaceVariant,
        MaterialTheme.colorScheme.primary.copy(alpha = .25f),
        MaterialTheme.colorScheme.primary.copy(alpha = .50f),
        MaterialTheme.colorScheme.primary.copy(alpha = .75f), MaterialTheme.colorScheme.primary)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.p2_reading_heatmap), style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StatisticValue(stringResource(R.string.p2_current_streak),
                stringResource(R.string.p2_streak_days, data.currentStreak), Modifier.weight(1f))
            StatisticValue(stringResource(R.string.p2_longest_streak),
                stringResource(R.string.p2_streak_days, data.longestStreak), Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(enabled = year > 1, onClick = { year--; selectedDate = null }) {
                Icon(Icons.Default.ChevronLeft, stringResource(R.string.previous_period))
            }
            Text(stringResource(R.string.p2_heatmap_year, year, data.activeDays), Modifier.weight(1f))
            IconButton(enabled = year < currentYear, onClick = { year++; selectedDate = null }) {
                Icon(Icons.Default.ChevronRight, stringResource(R.string.next_period))
            }
        }
        val slots = List(data.leadingEmptySlots) { null } + data.days
        val weekdays = remember { DateFormatSymbols(Locale.getDefault()).shortWeekdays }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                (0..6).forEach { day ->
                    Text(weekdays[(day + 1) % 7 + 1], Modifier.height(22.dp).width(36.dp),
                        style = MaterialTheme.typography.labelSmall)
                }
            }
            slots.chunked(7).forEach { week ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    week.forEach { day ->
                        if (day == null) Box(Modifier.size(22.dp)) else {
                            val level = when {
                                day.seconds <= 0 -> 0
                                day.seconds < 900 -> 1
                                day.seconds < 1800 -> 2
                                day.seconds < 3600 -> 3
                                else -> 4
                            }
                            val label = "${day.date}: ${durationText(day.seconds)}"
                            Box(Modifier.size(22.dp).background(if (day.future)
                                MaterialTheme.colorScheme.surface else colors[level])
                                .semantics { contentDescription = label }
                                .clickable(enabled = !day.future) { selectedDate = day.date })
                        }
                    }
                }
            }
        }
        selectedDate?.let { date ->
            data.days.firstOrNull { it.date == date }?.let { day ->
                Text("$date · ${durationText(day.seconds)}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(stringResource(R.string.p2_heatmap_legend), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
