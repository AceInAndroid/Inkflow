package com.aceinandroid.inkflow.ai

import android.content.Context
import com.aceinandroid.inkflow.core.*
import com.aceinandroid.inkflow.core.ai.*
import org.json.JSONArray
import org.json.JSONObject

class NativeAiStore(context: Context) {
    private companion object { val skillImportLock = Any() }
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

    private fun customSkills(): List<ReadingSkill> = SkillCodec.decodeStored(
        portable.getString("custom_skills", null) ?: prefs.getString("custom_skills", "[]") ?: "[]")

    fun skills(): ReadingSkillRegistry = runCatching { ReadingSkillRegistry(customSkills()) }
        .getOrElse { ReadingSkillRegistry() }

    fun previewSkillImport(text: String): SkillImportPreview =
        SkillImportPreview.create(SkillCodec.decode(text), ReadingSkillRegistry(customSkills()))

    fun previewSkillImport(bytes: ByteArray): SkillImportPreview =
        SkillImportPreview.create(SkillCodec.decode(bytes), ReadingSkillRegistry(customSkills()))

    /** Revalidate against current storage and commit the complete batch only after preview approval. */
    fun applySkillImport(preview: SkillImportPreview, overwrite: Boolean = false) = synchronized(skillImportLock) {
        val merged = preview.merge(ReadingSkillRegistry(customSkills()), overwrite)
        val encoded = SkillCodec.encodeStored(merged)
        check(portable.edit().putString("custom_skills", encoded).commit()) { "Cannot save skills" }
    }

    // Compatibility facade: importing never silently replaces an existing ID.
    fun importSkillJson(json: String) { applySkillImport(previewSkillImport(json)) }
    fun exportSkillJson(id: String): String = SkillCodec.encodeJson(requireNotNull(skills().find(id)) { "Unknown skill" })
    fun exportSkillMarkdown(id: String): String = SkillCodec.encodeMarkdown(requireNotNull(skills().find(id)) { "Unknown skill" })
    fun exportStoredSkills(): String = SkillCodec.encodeStored(customSkills())
    fun exportCustomSkillsJson(): String = SkillCodec.encodeJson(customSkills())

    /** Device-only selection/bindings. The portable skills store and backups never contain these. */
    fun selectedAgent(): ReadingAgent = ReadingAgentRegistry().resolve(prefs.getString("selected_agent", null))
    private fun availableAgent(id: String): ReadingAgent =
        requireNotNull(ReadingAgentRegistry().find(id)?.takeIf { it.enabled }) { "Agent is unavailable" }

    fun selectAgent(id: String) {
        prefs.edit().putString("selected_agent", availableAgent(id).id).apply()
    }
    private fun boundSkill(agent: ReadingAgent): String? =
        prefs.getString("agent_skill_binding.${agent.id}", null) ?: ReadingAgentRegistry.aliases.entries
            .filter { it.value == agent.id }.firstNotNullOfOrNull { prefs.getString("agent_skill_binding.${it.key}", null) }

    fun skillForAgent(id: String): ReadingSkill {
        val agent = availableAgent(id)
        return ReadingAgentRegistry().resolveSkill(agent.id, skills(), deviceSkillId = boundSkill(agent))
    }
    fun bindAgentSkill(agentId: String, skillId: String?) {
        val agent = availableAgent(agentId)
        require(skillId == null || skills().find(skillId) != null) { "Unknown skill" }
        val editor = prefs.edit().putString("agent_skill_binding.${agent.id}", skillId)
        ReadingAgentRegistry.aliases.filterValues { it == agent.id }.keys.forEach { editor.remove("agent_skill_binding.$it") }
        editor.apply()
    }

    /** Missing/unknown/disabled book choices use the device's available Agent, never a model call. */
    fun agentForBook(bookId: Long): ReadingAgent {
        require(bookId > 0) { "Invalid book ID" }
        val saved = prefs.getString("book_agent.$bookId", null)
        return ReadingAgentRegistry().resolveBookAgent(saved, prefs.getString("selected_agent", null))
    }
    fun setAgentForBook(bookId: Long, agentId: String?) {
        require(bookId > 0) { "Invalid book ID" }
        prefs.edit().putString("book_agent.$bookId", agentId?.let { availableAgent(it).id }).apply()
    }

    /** Binding is per book AND Agent; switching Agents does not destroy the previous choice. */
    fun skillForBook(bookId: Long, agentId: String? = null): ReadingSkill {
        require(bookId > 0) { "Invalid book ID" }
        val agent = agentId?.let(::availableAgent) ?: agentForBook(bookId)
        val saved = prefs.getString("book_skill_binding.$bookId.${agent.id}", null)
            ?: ReadingAgentRegistry.aliases.filterValues { it == agent.id }.keys.firstNotNullOfOrNull {
                prefs.getString("book_skill_binding.$bookId.$it", null)
            }
        return ReadingAgentRegistry().resolveSkill(agent.id, skills(), saved, boundSkill(agent))
    }
    fun setSkillForBook(bookId: Long, skillId: String?, agentId: String? = null) {
        require(bookId > 0) { "Invalid book ID" }
        val agent = agentId?.let(::availableAgent) ?: agentForBook(bookId)
        require(skillId == null || skills().find(skillId) != null) { "Unknown skill" }
        val editor = prefs.edit().putString("book_skill_binding.$bookId.${agent.id}", skillId)
        ReadingAgentRegistry.aliases.filterValues { it == agent.id }.keys.forEach { editor.remove("book_skill_binding.$bookId.$it") }
        editor.apply()
    }

    /** First successful request wins across all store instances. Accounting must never fail an answer. */
    fun recordUsage(request: AiRequest, result: AiRequestResult): Boolean = synchronized(NativeAiUsageJournal.lock) {
        try {
            val contract = NativeAiUsageJournal
            val now = System.currentTimeMillis()
            val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)
            val day = date.format(java.util.Date(now))
            val workload = AiWorkloads.registered[request.workload] ?: error("Unknown usage workload")
            val entry = JSONObject().put("requestId", request.id).put("workload", workload.id)
                .put("providerId", result.providerId).put("role", workload.role.name).put("model", result.model)
                .put("day", day).put("time", now).put("input", result.usage.input).put("output", result.usage.output)
                .put("estimated", result.usage.estimated).put("attempts", result.attempts)
            request.bookId?.let { entry.put("bookId", it) }
            request.generationAuthorization?.taskId?.let { entry.put("taskId", it) }
            require(contract.record(entry) != null) { "Invalid usage metadata" }
            val raw = prefs.getString(contract.KEY, null)
            val baseline = prefs.getString(contract.LEGACY_KEY, null)
            // A half migration is corrupt, never a reason to freeze the growing UI aggregate again.
            require((raw == null) == (baseline == null)) { "Incomplete usage migration" }
            val journal = raw?.let(contract::parse) ?: contract.empty()
            val oldRows = journal.getJSONArray("records")
            val seen = journal.getJSONObject("seen")
            val hash = contract.digest(request.id)
            if (seen.has(hash) || (0 until oldRows.length()).any {
                    oldRows.optJSONObject(it)?.optString("requestId") == request.id }) return@synchronized true
            val cutoff = now - contract.MAX_AGE_MS
            var truncated = journal.getBoolean("truncated")
            val rows = (0 until oldRows.length()).map { contract.canonicalRow(oldRows.getJSONObject(it)) }
                .sortedBy { contract.count(it.opt("time"))!! }.filter {
                    (contract.count(it.opt("time"))!! >= cutoff).also { keep -> if (!keep) truncated = true }
                }.toMutableList()
            rows += entry
            while (rows.size > contract.MAX_RECORDS) { rows.removeAt(0); truncated = true }
            val hashes = seen.keys().asSequence().map { key ->
                require(Regex("[0-9a-f]{64}").matches(key)) { "Invalid usage deduplication key" }
                key to (contract.count(seen.opt(key)) ?: error("Invalid usage deduplication time"))
            }.filter { it.second >= cutoff }.sortedBy { it.second }.toMutableList()
            hashes += hash to now
            while (hashes.size > contract.MAX_SEEN) hashes.removeAt(0)
            val retainedHashes = JSONObject().also { values -> hashes.forEach { (id, time) -> values.put(id, time) } }
            fun encode(): String = contract.empty().put("records", JSONArray(rows)).put("seen", retainedHashes)
                .put("truncated", truncated).toString()
            var encoded = encode()
            while (encoded.toByteArray(Charsets.UTF_8).size > contract.MAX_BYTES && rows.size > 1) {
                rows.removeAt(0); truncated = true; encoded = encode()
            }
            require(encoded.toByteArray(Charsets.UTF_8).size <= contract.MAX_BYTES) { "Usage journal too large" }
            val previous = prefs.getString("token_usage", "{}") ?: "{}"
            val all = contract.objectValue(previous)
            val key = "$day|${result.model}"
            val row = all.optJSONObject(key) ?: JSONObject()
            fun add(field: String, value: Long): Long {
                val old = if (row.has(field)) contract.count(row.opt(field)) ?: error("Invalid usage counter") else 0
                require(old <= Long.MAX_VALUE - value) { "Usage counter overflow" }
                return old + value
            }
            row.put("input", add("input", result.usage.input)).put("output", add("output", result.usage.output))
                .put("estimated", row.optBoolean("estimated") || result.usage.estimated)
            all.put(key, row)
            val oldestDay = date.format(java.util.Date(cutoff))
            all.keys().asSequence().filter { it.substringBefore('|') < oldestDay }.toList().forEach(all::remove)
            // One preference transaction: freeze BEFORE this result, append journal, update old UI totals.
            val editor = prefs.edit().putString(contract.KEY, encoded).putString("token_usage", all.toString())
            if (raw == null) editor.putString(contract.LEGACY_KEY, previous)
            editor.apply()
            true
        } catch (_: Exception) {
            // Best effort only; no error/body/config is logged or serialized. A broken store stays readable as a gap.
            runCatching { prefs.edit().putBoolean(NativeAiUsageJournal.INCOMPLETE_KEY, true).apply() }
            false
        }
    }
    fun usageSummary(): List<String> {
        val all = runCatching { JSONObject(prefs.getString("token_usage", "{}") ?: "{}") }.getOrDefault(JSONObject())
        return all.keys().asSequence().sortedDescending().take(60).map { key ->
            val row = all.getJSONObject(key)
            "$key · ${row.optLong("input")} + ${row.optLong("output")}${if (row.optBoolean("estimated")) " ≈" else ""}"
        }.toList()
    }
}
