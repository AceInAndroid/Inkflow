package com.aceinandroid.inkflow

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Only the legacy settings consumed by the native migration may survive another backup. */
internal object NativeLegacyPreferenceFilter {
    private val styleFields = setOf("fontSize", "fontFamily", "fontWeight", "lineHeight", "letterSpacing",
        "wordSpacing", "paragraphSpacing", "sideMargin", "topMargin", "bottomMargin", "indent",
        "maxColumnCount", "headingFontSize", "columnThreshold")
    private val backgroundFields = setOf("type", "path", "nightPath", "selectedMode", "alignment", "blur", "opacity", "autoAdjust")

    fun portable(document: JSONObject): JSONObject {
        if (document.optInt("__prefsBackupVersion", -1) != 1) throw IOException("Unsupported legacy preferences")
        val result = JSONObject().put("__prefsBackupVersion", 1)
        fun entry(key: String, type: String, transform: (Any) -> Any? = { it }) {
            val original = document.optJSONObject(key) ?: return
            if (original.optString("type") != type) return
            val value = original.opt("value") ?: return
            val typed = when (type) {
                "bool" -> value is Boolean
                "string" -> value is String && value.length <= 100_000
                "double" -> value is Number && value.toDouble().isFinite()
                else -> false
            }
            if (!typed) return
            val safe = runCatching { transform(value) }.getOrNull() ?: return
            result.put(key, JSONObject().put("type", type).put("value", safe))
        }
        entry("volumeKeyTurnPage", "bool")
        entry("autoAdjustReadingTheme", "bool")
        entry("ttsRate", "double") { it.takeIf { value -> (value as Number).toDouble() in 0.05..2.0 } }
        entry("ttsPitch", "double") { it.takeIf { value -> (value as Number).toDouble() in 0.5..2.0 } }
        entry("sortField", "string") { it.takeIf { value -> BookSortField.entries.any { field -> field.name.equals(value as String, ignoreCase = true) } || value in setOf("lastReadTime", "importTime") } }
        entry("sortOrder", "string") { it.takeIf { value -> value in setOf("ascending", "descending") } }
        entry("bgimgFit", "string") { it.takeIf { value -> value in setOf("cover", "stretch") } }
        entry("chapterSplitSelectedRuleId", "string") { it.takeIf { value -> (value as String).length in 1..128 } }
        entry("chapterSplitCustomRules", "string") { value ->
            val rules = JSONArray(value as String)
            require(rules.length() <= 50)
            JSONArray((0 until rules.length()).map { TxtChapterRule.fromJson(rules.getJSONObject(it)).toJson() }).toString()
        }
        entry("readStyle", "string") { filterObject(it as String, styleFields) }
        entry("readTheme", "string") { value ->
            filterObject(value as String, setOf("backgroundColor", "textColor", "backgroundImagePath")) { key, field ->
                if (key == "backgroundImagePath") field is String && (field.isEmpty() || safeLocalPath(field))
                else field is String && readerThemeColor(field) != null
            }
        }
        entry("bgimg", "string") { value ->
            val raw = value as String
            val objectValue = JSONObject(raw)
            if (objectValue.optString("type") == "none") JSONObject().put("type", "none").toString()
            else {
                ReaderBackgroundImage.parse(raw) // Reject URLs, traversal and unsupported image policies.
                filterObject(raw, backgroundFields)
            }
        }
        return result
    }

    private fun safeLocalPath(value: String): Boolean = !value.startsWith('/') && !value.contains('\\') &&
        !value.contains(':') && value.split('/').none { it == ".." }

    private fun filterObject(raw: String, allowed: Set<String>,
        valid: (String, Any) -> Boolean = { _, value -> value is String || value is Boolean || value is Number && value.toDouble().isFinite() }): String {
        val original = JSONObject(raw)
        val safe = JSONObject()
        original.keys().forEach { key ->
            val value = original.get(key)
            if (key in allowed && valid(key, value)) safe.put(key, value)
        }
        // Preserve valid legacy scalar formatting while never copying unknown nested containers.
        return if (safe.length() == original.length()) raw else safe.toString()
    }
}
