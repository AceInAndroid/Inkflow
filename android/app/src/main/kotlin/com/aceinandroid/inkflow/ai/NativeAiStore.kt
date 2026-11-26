package com.aceinandroid.inkflow.ai

import android.content.Context
import com.aceinandroid.inkflow.core.*
import com.aceinandroid.inkflow.core.ai.*
import org.json.JSONArray
import org.json.JSONObject

class NativeAiStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("native_ai_workspace", Context.MODE_PRIVATE)

    private val portable = context.applicationContext.getSharedPreferences("native_reading_skills", Context.MODE_PRIVATE)

    fun loadRegistry(): AiProviderRegistry {
        val providers = runCatching {
            val raw = prefs.getString("providers_encrypted", null)?.let(NativeAiSecrets::decrypt) ?: prefs.getString("providers_v1", "[]")
            val array = JSONArray(raw)
            buildList { for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val type = AiProviderType.entries.firstOrNull { it.name == obj.optString("type") } ?: continue
                val keys = obj.optJSONArray("keys") ?: JSONArray()
                val keyList = (0 until keys.length()).map { keys.getString(it) }
                add(RegisteredAiProvider(obj.getString("id"), obj.optString("name", type.displayName),
                    AiProviderConfig(type, obj.optString("url", type.defaultBaseUrl), keyList.firstOrNull().orEmpty(),
                        obj.optString("model", type.defaultModel), obj.optDouble("temperature", .4).toFloat().coerceIn(0f, 2f)), keyList))
            } }
        }.getOrDefault(emptyList())
        if (providers.isEmpty()) {
            val config = legacyConfig()
            return AiProviderRegistry(if (config.isConfigured) listOf(RegisteredAiProvider("legacy", config.providerType.displayName, config)) else emptyList())
        }
        return AiProviderRegistry(providers, prefs.getString("conversation_id", null) ?: providers.first().id, prefs.getString("lightweight_id", null))
    }
    fun saveRegistry(registry: AiProviderRegistry) {
        val array = JSONArray()
        registry.providers.forEach { p -> array.put(JSONObject().apply {
            put("id", p.id); put("name", p.name); put("type", p.config.providerType.name)
            put("url", p.config.baseUrl.trim()); put("model", p.config.model.trim())
            put("temperature", p.config.temperature.toDouble()); put("keys", JSONArray(p.apiKeys))
        }) }
        prefs.edit().putString("providers_encrypted", NativeAiSecrets.encrypt(array.toString())).remove("providers_v1").putString("conversation_id", registry.conversationId)
            .putString("lightweight_id", registry.lightweightId)
            .remove("provider_type").remove("base_url").remove("api_key").remove("model").remove("temperature").apply()
    }
    fun loadConfig(): AiProviderConfig = loadRegistry().candidates(AiProviderRole.CONVERSATION).firstOrNull()?.second ?: AiProviderConfig()
    fun saveConfig(config: AiProviderConfig) {
        val old = loadRegistry()
        val id = old.conversationId ?: "primary"
        val replacement = RegisteredAiProvider(id, config.providerType.displayName, config)
        saveRegistry(AiProviderRegistry(old.providers.filterNot { it.id == id } + replacement, id, old.lightweightId))
    }
    private fun legacyConfig(): AiProviderConfig {
        val type = AiProviderType.entries.firstOrNull { it.name == prefs.getString("provider_type", null) } ?: AiProviderType.OPENAI_COMPATIBLE
        return AiProviderConfig(type, prefs.getString("base_url", null) ?: type.defaultBaseUrl,
            prefs.getString("api_key", "").orEmpty(), prefs.getString("model", null) ?: type.defaultModel,
            prefs.getFloat("temperature", .4f).coerceIn(0f, 2f))
    }
    fun loadMessages(bookId: Long): List<AiChatMessage> = AiSessionRepository.decodeMessages(prefs.getString("chat_book_$bookId", "[]") ?: "[]")
    fun saveMessages(bookId: Long, messages: List<AiChatMessage>) { prefs.edit().putString("chat_book_$bookId", AiSessionRepository.encodeMessages(messages).toString()).apply() }
    fun clearMessages(bookId: Long) { prefs.edit().remove("chat_book_$bookId").apply() }
    fun activeSession(bookId: Long): String? = prefs.getString("active_session_$bookId", null)
    fun setActiveSession(bookId: Long, id: String) { prefs.edit().putString("active_session_$bookId", id).apply() }

    fun skills(): ReadingSkillRegistry = ReadingSkillRegistry(runCatching {
        val array = JSONArray(portable.getString("custom_skills", null) ?: prefs.getString("custom_skills", "[]"))
        (0 until array.length()).map { array.getJSONObject(it).let { o -> ReadingSkill(o.getString("id"), o.getString("title"), o.getString("summary"), o.getString("instructions")) } }
    }.getOrDefault(emptyList()))
    fun importSkillJson(json: String) {
        require(json.length <= 128000) { "Skill file is too large" }
        val value = JSONObject(json)
        val skill = ReadingSkill(value.getString("id"), value.getString("title"), value.getString("summary"), value.getString("instructions"))
        val custom = skills().skills.filter { it.id.startsWith("custom.") && it.id != skill.id } + skill
        ReadingSkillRegistry(custom) // Validate before replacing existing data.
        portable.edit().putString("custom_skills", JSONArray(custom.map { JSONObject().put("id", it.id).put("title", it.title).put("summary", it.summary).put("instructions", it.instructions) }).toString()).apply()
    }
    fun exportSkillJson(id: String): String = skills().find(id)?.let {
        JSONObject().put("id", it.id).put("title", it.title).put("summary", it.summary).put("instructions", it.instructions).toString(2)
    } ?: error("Unknown skill")

    @Synchronized fun recordUsage(model: String, usage: AiTokenUsage) {
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date())
        val key = "$date|$model"
        val all = JSONObject(prefs.getString("token_usage", "{}") ?: "{}")
        val row = all.optJSONObject(key) ?: JSONObject()
        row.put("input", row.optLong("input") + usage.input).put("output", row.optLong("output") + usage.output)
            .put("estimated", row.optBoolean("estimated") || usage.estimated)
        all.put(key, row)
        // Limit the local log while retaining date/model totals within the window.
        all.keys().asSequence().filter { it.substringBefore('|') < java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date(System.currentTimeMillis() - 366L * 86400000)) }.toList().forEach(all::remove)
        prefs.edit().putString("token_usage", all.toString()).apply()
    }
    fun usageSummary(): List<String> {
        val all = runCatching { JSONObject(prefs.getString("token_usage", "{}") ?: "{}") }.getOrDefault(JSONObject())
        return all.keys().asSequence().sortedDescending().take(60).map { key ->
            val row = all.getJSONObject(key)
            "$key · ${row.optLong("input")} + ${row.optLong("output")}${if (row.optBoolean("estimated")) " ≈" else ""}"
        }.toList()
    }
}
