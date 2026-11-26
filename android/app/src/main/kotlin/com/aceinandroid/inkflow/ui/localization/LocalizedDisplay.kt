package com.aceinandroid.inkflow.ui.localization

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.aceinandroid.inkflow.R
import com.aceinandroid.inkflow.ai.NativeReadingUndoResult
import com.aceinandroid.inkflow.core.AiProviderType
import com.aceinandroid.inkflow.core.ai.ReadingSkill
import com.aceinandroid.inkflow.core.ai.RegisteredAiProvider
import com.aceinandroid.inkflow.core.translate.TranslationProvider
import java.util.Locale

/** Display projections only. Never change persisted IDs, custom content or model instructions. */
private val builtInSkillStrings = mapOf(
    "reading.general" to (R.string.localization_skill_general_title to R.string.localization_skill_general_summary),
    "reading.socratic" to (R.string.localization_skill_socratic_title to R.string.localization_skill_socratic_summary),
    "reading.argument" to (R.string.localization_skill_argument_title to R.string.localization_skill_argument_summary),
    "reading.characters" to (R.string.localization_skill_characters_title to R.string.localization_skill_characters_summary),
    "reading.history" to (R.string.localization_skill_history_title to R.string.localization_skill_history_summary),
    "reading.language" to (R.string.localization_skill_language_title to R.string.localization_skill_language_summary),
    "reading.recap" to (R.string.localization_skill_recap_title to R.string.localization_skill_recap_summary),
    "reading.concepts" to (R.string.localization_skill_concepts_title to R.string.localization_skill_concepts_summary),
    "reading.critical" to (R.string.localization_skill_critical_title to R.string.localization_skill_critical_summary),
    "reading.reflect" to (R.string.localization_skill_reflect_title to R.string.localization_skill_reflect_summary),
)

@Composable
fun localizedSkillTitle(skill: ReadingSkill): String =
    builtInSkillStrings[skill.id]?.let { stringResource(it.first) } ?: skill.title

@Composable
fun localizedSkillSummary(skill: ReadingSkill): String =
    builtInSkillStrings[skill.id]?.let { stringResource(it.second) } ?: skill.summary

@StringRes
fun taskStatusString(status: String): Int = when (status) {
    "queued" -> R.string.localization_task_queued
    "running" -> R.string.localization_task_running
    "paused" -> R.string.localization_task_paused
    "completed" -> R.string.localization_task_completed
    "failed" -> R.string.localization_task_failed
    "cancelled" -> R.string.localization_task_cancelled
    else -> R.string.localization_task_unknown
}

fun Context.localizedUndoResult(result: NativeReadingUndoResult): String = getString(when (result) {
    NativeReadingUndoResult.UNDONE -> R.string.localization_undo_undone
    NativeReadingUndoResult.ALREADY_UNDONE -> R.string.localization_undo_already_undone
    NativeReadingUndoResult.CONFLICT -> R.string.localization_undo_conflict
    NativeReadingUndoResult.EXPIRED -> R.string.localization_undo_expired
    NativeReadingUndoResult.MISSING -> R.string.localization_undo_missing
    NativeReadingUndoResult.UNSUPPORTED -> R.string.localization_undo_unsupported
})

@Composable
fun localizedAiProvider(type: AiProviderType): String = stringResource(when (type) {
    AiProviderType.OPENAI_COMPATIBLE -> R.string.localization_provider_openai
    AiProviderType.ANTHROPIC -> R.string.localization_provider_anthropic
    AiProviderType.DEEPSEEK -> R.string.localization_provider_deepseek
    AiProviderType.OLLAMA -> R.string.localization_provider_ollama
    AiProviderType.GEMINI_OPENAI -> R.string.localization_provider_gemini
})

@Composable
fun localizedProviderName(provider: RegisteredAiProvider): String =
    if (provider.name == provider.config.providerType.displayName) localizedAiProvider(provider.config.providerType)
    else provider.name

@Composable
fun localizedTranslationProvider(provider: TranslationProvider): String = stringResource(when (provider) {
    TranslationProvider.AI -> R.string.localization_translation_ai
    TranslationProvider.DEEPL -> R.string.localization_translation_deepl
    TranslationProvider.MICROSOFT -> R.string.localization_translation_microsoft
    TranslationProvider.GOOGLE -> R.string.localization_translation_google
})

/** Resolve a label in the app resource locale while retaining the provider's original language code. */
@Composable
fun localizedLanguageName(languageTag: String): String {
    if (languageTag.trim().equals("auto", ignoreCase = true)) return stringResource(R.string.localization_language_detect)
    val locale = LocalConfiguration.current.locales[0]
    val language = Locale.forLanguageTag(languageTag.trim())
    return if (language.language.isBlank() || language.language == "und") languageTag
    else language.getDisplayName(locale)
}
