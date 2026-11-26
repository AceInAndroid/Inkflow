package com.aceinandroid.inkflow.core.translate

/** No execution on construction, opening a sheet, or changing language/provider preferences. */
enum class TranslationProvider { AI, DEEPL, MICROSOFT, GOOGLE }

data class TranslationRequest(
    val text: String,
    val targetLanguage: String,
    val sourceLanguage: String = "auto",
    val provider: TranslationProvider = TranslationProvider.AI,
    val userInitiated: Boolean = false,
) {
    fun validateForDispatch() {
        require(userInitiated) { "Translation requires an explicit user action" }
        require(text.isNotBlank() && text.length <= 12000) { "Select between 1 and 12000 characters" }
        val language = Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8}){0,2}")
        require(language.matches(targetLanguage)) { "Invalid target language" }
        require(sourceLanguage == "auto" || language.matches(sourceLanguage)) { "Invalid source language" }
    }
}

data class TranslationResult(val text: String, val provider: TranslationProvider, val targetLanguage: String)

/** AI dispatch is handed to the existing reading action/gateway; this boundary owns no model client. */
class TranslationDispatcher(
    private val onAskAi: (TranslationRequest) -> Unit,
    private val translateService: (TranslationRequest) -> TranslationResult,
) {
    fun dispatch(request: TranslationRequest): TranslationResult? {
        request.validateForDispatch()
        if (request.provider == TranslationProvider.AI) {
            onAskAi(request)
            return null
        }
        return translateService(request)
    }
}
