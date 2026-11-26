package com.aceinandroid.inkflow.ui.reader

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aceinandroid.inkflow.R
import com.aceinandroid.inkflow.core.PaginationSummary
import com.aceinandroid.inkflow.core.SelectionRangeController
import com.aceinandroid.inkflow.core.TextSelectionRange

@Composable
internal fun SelectionRangeControls(
    controller: SelectionRangeController,
    selection: TextSelectionRange,
    currentPage: Int,
    pagination: PaginationSummary?,
    onChange: (TextSelectionRange) -> Unit,
    onShowPage: (Int) -> Unit,
) {
    var endpoint by remember { mutableStateOf(SelectionRangeController.Endpoint.END) }
    var unit by remember { mutableStateOf(SelectionRangeController.Unit.CHARACTER) }
    fun current(): SelectionRangeController.Snapshot? {
        val state = controller.snapshot?.takeIf { it.range == selection } ?: controller.begin(selection, currentPage)
        val page = pagination?.pages?.getOrNull(currentPage)
        return if (state != null && page != null) controller.confirmVisiblePage(page, state.revision) ?: state else state
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SelectionRangeController.Endpoint.entries.forEach { value ->
            FilterChip(modifier = Modifier.sizeIn(minHeight = 56.dp), selected = endpoint == value, onClick = { endpoint = value }, label = {
                Text(stringResource(if (value == SelectionRangeController.Endpoint.START)
                    R.string.migration_selection_start else R.string.migration_selection_end))
            })
        }
        SelectionRangeController.Unit.entries.forEach { value ->
            FilterChip(modifier = Modifier.sizeIn(minHeight = 56.dp), selected = unit == value, onClick = { unit = value }, label = {
                Text(stringResource(when (value) {
                    SelectionRangeController.Unit.CHARACTER -> R.string.migration_character
                    SelectionRangeController.Unit.WORD -> R.string.migration_word
                    SelectionRangeController.Unit.SENTENCE -> R.string.migration_sentence
                }))
            })
        }
        listOf(false, true).forEach { forward ->
            TextButton(onClick = {
                current()?.let { controller.adjust(endpoint, unit, forward, it.revision) }?.let { onChange(it.range) }
            }, modifier = Modifier.sizeIn(minWidth = 56.dp, minHeight = 56.dp)) {
                Text(stringResource(if (forward) R.string.migration_endpoint_forward else R.string.migration_endpoint_back))
            }
        }
        if (pagination != null) {
            val endPage = pagination.pageIndexForOffset((selection.end - 1).coerceAtLeast(0))
            TextButton(onClick = { onShowPage(endPage) }) {
                Text(stringResource(R.string.migration_selection_show_end))
            }
            TextButton(enabled = currentPage == endPage && endPage + 1 < pagination.pageCount, onClick = {
                current()?.let { state -> pagination.pages.getOrNull(endPage + 1)?.let { page ->
                    controller.extendToPage(page, state.revision)
                } }?.let { state -> onChange(state.range); state.visiblePage?.let(onShowPage) }
            }) { Text(stringResource(R.string.migration_selection_extend)) }
            TextButton(enabled = controller.snapshot?.canRetract == true, onClick = {
                current()?.let { controller.retract(it.revision) }?.let { state ->
                    onChange(state.range); state.visiblePage?.let(onShowPage)
                }
            }) { Text(stringResource(R.string.migration_selection_retract)) }
        }
    }
}
