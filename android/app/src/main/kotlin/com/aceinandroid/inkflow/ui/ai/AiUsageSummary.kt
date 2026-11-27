package com.aceinandroid.inkflow.ui.ai

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aceinandroid.inkflow.BookStore
import com.aceinandroid.inkflow.R
import com.aceinandroid.inkflow.ai.NativeAiCostRepository
import com.aceinandroid.inkflow.ai.NativeAiCostSnapshot
import com.aceinandroid.inkflow.core.ai.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.NumberFormat

/** A read-only projection: opening, grouping and refreshing usage never contact a provider. */
@Composable
internal fun AiUsageSummary(revision: Int, providers: Map<String, String>) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val number = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    var refresh by remember { mutableIntStateOf(0) }
    var dimensionName by rememberSaveable { mutableStateOf(AiCostDimension.MODEL.name) }
    val dimension = AiCostDimension.valueOf(dimensionName)
    var snapshot by remember { mutableStateOf<NativeAiCostSnapshot?>(null) }
    var books by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(context, revision, refresh) {
        val result = withContext(Dispatchers.IO) {
            NativeAiCostRepository(context).read() to runCatching {
                BookStore(context).use { store -> store.all().associate { it.id.toString() to it.title } }
            }.getOrDefault(emptyMap())
        }
        snapshot = result.first
        books = result.second
    }
    Text(stringResource(R.string.ai_token_usage), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.usage_scope), style = MaterialTheme.typography.bodySmall)
    val current = snapshot
    if (current == null) LinearProgressIndicator(Modifier.fillMaxWidth())
    else {
        val summary = remember(current, dimension) { current.summarize(setOf(dimension)) }
        val totals = summary.totals
        Text(stringResource(R.string.usage_quality,
            number.format(totals.reported.total), number.format(totals.estimated.total),
            number.format(totals.mixedOrUnknown.total)))
        Text(stringResource(R.string.usage_requests, totals.requestCount?.let(number::format)
            ?: context.getString(R.string.usage_unknown)), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AiCostDimension.entries.forEach { item ->
                FilterChip(selected = item == dimension, onClick = { dimensionName = item.name },
                    label = { Text(stringResource(dimensionLabel(item))) })
            }
        }
        if (summary.groups.isEmpty()) Text(stringResource(R.string.usage_empty))
        summary.groups.sortedByDescending { it.totals.tokens.total }.take(100).forEach { group ->
            val key = group.key[dimension]
            val label = when (dimension) {
                AiCostDimension.BOOK -> books[key] ?: key?.let { context.getString(R.string.usage_book, it) }
                AiCostDimension.PROVIDER -> providers[key] ?: key
                else -> key
            } ?: context.getString(R.string.usage_unknown)
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.usage_io, number.format(group.totals.tokens.input),
                    number.format(group.totals.tokens.output)), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (summary.groups.size > 100) Text(stringResource(R.string.usage_limit), style = MaterialTheme.typography.bodySmall)
        if (current.gaps.isNotEmpty()) Text(stringResource(R.string.usage_gaps), style = MaterialTheme.typography.bodySmall)
    }
    TextButton(onClick = { refresh++ }) { Text(stringResource(R.string.usage_refresh)) }
}

private fun dimensionLabel(dimension: AiCostDimension): Int = when (dimension) {
    AiCostDimension.BOOK -> R.string.usage_by_book
    AiCostDimension.TASK -> R.string.usage_by_task
    AiCostDimension.REQUEST -> R.string.usage_by_request
    AiCostDimension.PROVIDER -> R.string.usage_by_provider
    AiCostDimension.ROLE -> R.string.usage_by_role
    AiCostDimension.WORKLOAD -> R.string.usage_by_workload
    AiCostDimension.MODEL -> R.string.usage_by_model
    AiCostDimension.DAY -> R.string.usage_by_day
}
