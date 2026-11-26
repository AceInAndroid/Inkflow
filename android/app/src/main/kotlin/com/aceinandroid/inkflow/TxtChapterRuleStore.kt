package com.aceinandroid.inkflow

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class TxtChapterRule(
    val id: String,
    val name: String,
    val pattern: String,
    val samples: List<String> = emptyList(),
    val caseSensitive: Boolean = false,
    val multiLine: Boolean = true,
) {
    fun expression(): Regex {
        require(pattern.isNotBlank() && pattern.length <= 1024) { "Invalid chapter rule pattern" }
        val options = buildSet {
            if (!caseSensitive) add(RegexOption.IGNORE_CASE)
            if (multiLine) add(RegexOption.MULTILINE)
        }
        return Regex(pattern, options)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("pattern", pattern)
        put("samples", JSONArray(samples))
        put("caseSensitive", caseSensitive)
        put("multiLine", multiLine)
    }

    companion object {
        fun fromJson(value: JSONObject): TxtChapterRule {
            val samples = value.optJSONArray("samples") ?: JSONArray()
            require(samples.length() <= 20) { "Too many chapter rule samples" }
            val rule = TxtChapterRule(
                value.getString("id"), value.getString("name"), value.getString("pattern"),
                (0 until samples.length()).map(samples::getString),
                value.optBoolean("caseSensitive", false), value.optBoolean("multiLine", true),
            )
            require(rule.id.length in 1..128 && rule.name.length in 1..100 &&
                rule.samples.all { it.length <= 300 }) { "Invalid chapter rule" }
            rule.expression()
            return rule
        }
    }
}

internal class TxtChapterRuleStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "native_txt_rules", Context.MODE_PRIVATE)

    fun customRules(): List<TxtChapterRule> = runCatching {
        val entries = JSONArray(preferences.getString("custom_rules", "[]"))
        (0 until entries.length()).map { TxtChapterRule.fromJson(entries.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun selectedRuleId(): String = preferences.getString("selected_rule_id", DEFAULT_ID)
        .orEmpty().ifBlank { DEFAULT_ID }

    fun activeRule(): TxtChapterRule = (builtins + customRules())
        .firstOrNull { it.id == selectedRuleId() } ?: builtins.first()

    fun ruleForBook(bookId: Long): Regex = preferences.getString("book:$bookId", null)
        ?.let { runCatching { TxtChapterRule.fromJson(JSONObject(it)).expression() }.getOrNull() }
        ?: builtins.first().expression()

    fun bindBook(bookId: Long, rule: TxtChapterRule) {
        check(preferences.edit().putString("book:$bookId", rule.toJson().toString()).commit()) {
            "Cannot save TXT chapter rule"
        }
    }

    fun forgetBook(bookId: Long) {
        preferences.edit().remove("book:$bookId").apply()
    }

    fun select(id: String) {
        require((builtins + customRules()).any { it.id == id }) { "Unknown chapter rule" }
        check(preferences.edit().putString("selected_rule_id", id).commit()) {
            "Cannot save chapter rule"
        }
    }

    fun saveCustom(rule: TxtChapterRule) {
        TxtChapterRule.fromJson(rule.toJson())
        require(builtins.none { it.id == rule.id }) { "Built-in chapter rule cannot be changed" }
        val rules = customRules().filterNot { it.id == rule.id } + rule
        require(rules.size <= 50) { "Too many chapter rules" }
        check(preferences.edit().putString("custom_rules", JSONArray(rules.map { it.toJson() }).toString())
            .commit()) { "Cannot save chapter rule" }
    }

    fun deleteCustom(id: String) {
        val rules = customRules()
        require(rules.any { it.id == id }) { "Unknown chapter rule" }
        val editor = preferences.edit().putString("custom_rules",
            JSONArray(rules.filterNot { it.id == id }.map { it.toJson() }).toString())
        if (selectedRuleId() == id) editor.putString("selected_rule_id", DEFAULT_ID)
        check(editor.commit()) { "Cannot delete chapter rule" }
    }

    fun importFlutterPreferences(document: JSONObject) {
        val raw = document.optJSONObject("chapterSplitCustomRules")
            ?.takeIf { it.optString("type") == "string" }?.optString("value")
        val rules = runCatching {
            val values = JSONArray(raw ?: "[]")
            require(values.length() <= 50)
            (0 until values.length()).map { TxtChapterRule.fromJson(values.getJSONObject(it)) }
                .filterNot { candidate -> builtins.any { it.id == candidate.id } }
        }.getOrDefault(emptyList())
        val selected = document.optJSONObject("chapterSplitSelectedRuleId")
            ?.takeIf { it.optString("type") == "string" }?.optString("value")
        val selectedId = selected?.takeIf { id -> (builtins + rules).any { it.id == id } } ?: DEFAULT_ID
        check(preferences.edit().clear()
            .putString("custom_rules", JSONArray(rules.map { it.toJson() }).toString())
            .putString("selected_rule_id", selectedId).commit()) {
            "Cannot restore TXT chapter rules"
        }
    }

    companion object {
        const val DEFAULT_ID = "default_chapter_rule"
        fun newId(): String = UUID.randomUUID().toString()

        val builtins = listOf(
            TxtChapterRule(DEFAULT_ID, "Mixed languages", TxtBook.DEFAULT_HEADING_PATTERN,
                listOf("第一章 起始之地", "Chapter 12: The Journey", "Book 1 - Dawn")),
            TxtChapterRule("cn_only_numeric", "Chinese chapters",
                "^\\s*第[一二三四五六七八九十零〇百千万两0-9]+章(?:[ ：:.-].*)?$",
                listOf("第一章 少年出山", "第二十章 ：终极之战")),
            TxtChapterRule("en_chapter_number", "English chapters",
                "^\\s*chapter\\s+\\d+(?:[ .:-].*)?$",
                listOf("Chapter 1: Beginning", "chapter 23 - A twist")),
            TxtChapterRule("en_volume_number", "English volumes/books",
                "^\\s*(volume|book)\\s+\\d+(?:[ .:-].*)?$",
                listOf("Volume 1: Arrival", "Book 2 - Secrets")),
        )
    }
}
