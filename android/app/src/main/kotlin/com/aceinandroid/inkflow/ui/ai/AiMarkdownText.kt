package com.aceinandroid.inkflow.ui.ai

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

@Composable
internal fun AiMarkdownText(markdown: String) {
    val inline = Regex("(\\*\\*[^*]+\\*\\*|`[^`]+`)")
    Column {
        markdown.replace(Regex("\\[\\[source:[A-Za-z0-9_.:-]{1,180}]]"), "").lines().forEach { line ->
            val heading = line.takeWhile { it == '#' }.length.takeIf { it in 1..6 && line.getOrNull(it) == ' ' } ?: 0
            val content = if (heading > 0) line.drop(heading + 1) else line.replace(Regex("^[-*] "), "• ")
            Text(buildAnnotatedString {
                var offset = 0
                inline.findAll(content).forEach { match ->
                    append(content.substring(offset, match.range.first))
                    if (match.value.startsWith("**")) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(match.value.drop(2).dropLast(2)) }
                    else withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(match.value.drop(1).dropLast(1)) }
                    offset = match.range.last + 1
                }
                append(content.substring(offset))
            }, style = when (heading) {
                1 -> MaterialTheme.typography.titleLarge
                2, 3 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.bodyMedium
            })
        }
    }
}
