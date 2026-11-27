package com.aceinandroid.inkflow

import android.content.Context
import com.aceinandroid.inkflow.core.ai.SkillCodec
import com.aceinandroid.inkflow.ai.NativeAiStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

internal object NativeSettingsBackup {
    const val ENTRY = "native/settings.json"
    private val preferenceNames = listOf(
        "native_reader_appearance", "native_narration",
        "native_reader_positions", "native_reader_text_positions", "native_txt_rules",
        "native_bookshelf", "native_reading_skills",
    )

    fun capture(context: Context): JSONObject = JSONObject().apply {
        put("version", 5)
        preferenceNames.forEach { name ->
            val entries = JSONObject()
            val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            // The Skill export boundary preserves the legacy single-key fallback. Never
            // enumerate the private AI workspace or include its providers/selection/bindings.
            val settings = if (name == "native_reading_skills") {
                mapOf("custom_skills" to NativeAiStore(context).exportStoredSkills())
            } else preferences.all
            settings.toSortedMap().filterKeys { !deviceLocal(it) && !hardwareLocal(name, it) }
                .forEach { (key, value) ->
                    val entry = JSONObject()
                    when (value) {
                        is Boolean -> entry.put("type", "bool").put("value", value)
                        is Int -> entry.put("type", "int").put("value", value)
                        is Float -> {
                            if (!value.isFinite()) throw IOException("Invalid native preference: $key")
                            entry.put("type", "float").put("value", value.toDouble())
                        }
                        is String -> entry.put("type", "string").put("value", value)
                        else -> throw IOException("Unsupported native preference: $key")
                    }
                    entries.put(key, entry)
                }
            put(name, entries)
        }
    }.also(::parse)

    fun read(file: File): JSONObject = try {
        parse(JSONObject(file.readText(Charsets.UTF_8)))
    } catch (failure: Exception) {
        throw IOException("Invalid native backup settings", failure)
    }

    fun parse(document: JSONObject): JSONObject {
        val version = document.opt("version")
        if (version !is Number || version.toInt() !in 1..5 || version.toDouble() != version.toInt().toDouble())
            throw IOException("Unsupported native settings version")
        preferenceNames.forEach { name ->
            if (!included(name, version.toInt())) return@forEach
            val entries = document.optJSONObject(name) ?: throw IOException("Missing native settings: $name")
            if (entries.length() > 10_000) throw IOException("Too many native settings")
            val keys = entries.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key.length > 256 || '\u0000' in key) throw IOException("Invalid native setting name")
                // v1–v4 exported hardware policy. Ignore it, including obsolete value formats.
                if (hardwareLocal(name, key) && version.toInt() < 5) continue
                if (deviceLocal(key) || hardwareLocal(name, key))
                    throw IOException("Device-local setting in portable backup: $key")
                val entry = entries.optJSONObject(key) ?: throw IOException("Invalid native setting: $key")
                val value = entry.opt("value")
                val type = entry.optString("type")
                val expected = when (name) {
                    "native_reader_appearance" -> mapOf(
                        "font_size" to "int", "line_spacing" to "float",
                        "palette" to "string", "font" to "string",
                        "custom_background" to "string", "custom_foreground" to "string",
                        "custom_font_id" to "string", "custom_fonts" to "string",
                        "cjk_font" to "string", "cjk_custom_font_id" to "string",
                        "latin_font" to "string", "latin_custom_font_id" to "string",
                        "background_image" to "string", "background_image_fit" to "string",
                        "volume_key_turn_page" to "bool", "swipe_to_turn_page" to "bool", "two_page_layout" to "bool",
                        "edge_selection_enabled" to "bool",
                        "reader_engine_mode" to "string", "bookshelf_view_mode" to "string",
                        "page_margin" to "int", "paragraph_spacing" to "int", "first_line_indent" to "int",
                        "text_alignment" to "string", "show_header" to "bool", "show_footer" to "bool",
                        "show_time" to "bool", "show_battery" to "bool",
                    )[key]
                    "native_narration" -> mapOf(
                        "rate" to "float", "pitch" to "float",
                        "voice" to "string", "continuous" to "bool",
                    )[key]
                    "native_reader_positions" -> if (key.matches(Regex("[0-9]+"))) "int" else null
                    "native_reader_text_positions" ->
                        if (key.matches(Regex("[0-9]+:(chapter|offset)"))) "int" else null
                    "native_txt_rules" -> if (key in setOf("selected_rule_id", "custom_rules") ||
                        key.matches(Regex("book:[0-9]+"))) "string" else null
                    "native_reading_skills" -> if (key == "custom_skills") "string" else null
                    "native_bookshelf" -> when (key) {
                        "sort_field" -> "string"
                        "descending" -> "bool"
                        else -> null
                    }
                    else -> null
                }
                val valid = expected == type && when (type) {
                    "bool" -> value is Boolean
                    "int" -> value is Number && value.toLong() in Int.MIN_VALUE..Int.MAX_VALUE &&
                        value.toDouble() == value.toLong().toDouble()
                    "float" -> value is Number && value.toFloat().isFinite()
                    "string" -> value is String
                    else -> false
                }
                if (!valid || (name == "native_reader_appearance" &&
                        key in setOf("cjk_font", "latin_font") && ReaderFont.entries.none { it.name == value }) ||
                    (name == "native_reader_appearance" && key in setOf("cjk_custom_font_id", "latin_custom_font_id") &&
                        (value !is String || !importedReaderFontIdPattern.matches(value))) ||
                    (name == "native_reading_skills" && !validCustomSkills(value as? String)) || (name == "native_reader_appearance" &&
                        key in setOf("custom_background", "custom_foreground") &&
                        readerThemeColor(value as? String) == null) ||
                    (name == "native_reader_appearance" && key == "background_image" &&
                        runCatching { ReaderBackgroundImage.parse(value as String) }.isFailure) ||
                    (name == "native_reader_appearance" && key == "background_image_fit" &&
                        value !in setOf("cover", "stretch")) ||
                    (name == "native_txt_rules" && !validTxtRuleSetting(key, value as? String)) ||
                    (name == "native_bookshelf" && key == "sort_field" &&
                        BookSortField.entries.none { it.name == value }))
                    throw IOException("Invalid native setting: $key")
            }
        }
        return document
    }

    private fun validCustomSkills(raw: String?): Boolean = runCatching {
        SkillCodec.decodeStored(requireNotNull(raw))
    }.isSuccess

    private fun included(name: String, version: Int): Boolean = when (name) {
        "native_reading_skills" -> version >= 4
        "native_txt_rules" -> version >= 2
        "native_bookshelf" -> version >= 3
        else -> true
    }

    private fun hardwareLocal(name: String, key: String): Boolean =
        name == "native_reader_appearance" && key.startsWith("eink")

    // Language, agent selection and bindings belong to this device, even during restore.
    private fun deviceLocal(key: String): Boolean = key in setOf(
        "language", "locale", "app_language", "device_language", "selected_agent", "agent_selection",
    ) || key.startsWith("agent_skill_binding.") || key.startsWith("book_agent.") ||
        key.startsWith("book_skill_binding.")

    fun apply(context: Context, document: JSONObject) {
        parse(document)
        preferenceNames.forEach { name ->
            val entries = document.optJSONObject(name) ?: JSONObject()
            if (!included(name, document.getInt("version")) || !document.has(name)) return@forEach
            val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val editor = preferences.edit()
            val portableKeys = if (name == "native_reading_skills") listOf("custom_skills")
                else preferences.all.keys.filterNot { deviceLocal(it) || hardwareLocal(name, it) }
            portableKeys.forEach(editor::remove)
            if (name == "native_reading_skills" && !entries.has("custom_skills"))
                editor.putString("custom_skills", SkillCodec.encodeStored(emptyList()))
            val keys = entries.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (hardwareLocal(name, key)) continue
                val entry = entries.getJSONObject(key)
                when (entry.getString("type")) {
                    "bool" -> editor.putBoolean(key, entry.getBoolean("value"))
                    "int" -> editor.putInt(key, entry.getInt("value"))
                    "float" -> editor.putFloat(key, entry.getDouble("value").toFloat())
                    "string" -> editor.putString(key, entry.getString("value"))
                }
            }
            if (!editor.commit()) throw IOException("Cannot restore native settings: $name")
        }
    }

    /** All selected imported faces; archive already carries the complete existing font library. */
    fun selectedFontIds(document: JSONObject): Set<String> = listOf(
        "custom_font_id", "cjk_custom_font_id", "latin_custom_font_id",
    ).mapNotNull { key ->
        document.optJSONObject("native_reader_appearance")?.optJSONObject(key)?.opt("value") as? String
    }.toSet()

    fun selectedFontId(document: JSONObject): String? =
        document.optJSONObject("native_reader_appearance")?.optJSONObject("custom_font_id")
            ?.opt("value") as? String

    fun selectedBackgroundImage(document: JSONObject): ReaderBackgroundImage? =
        document.optJSONObject("native_reader_appearance")?.optJSONObject("background_image")
            ?.optString("value")?.let { ReaderBackgroundImage.parse(it) }

    private fun validTxtRuleSetting(key: String, value: String?): Boolean = runCatching {
        require(value != null && value.length <= 100_000)
        when (key) {
            "selected_rule_id" -> require(value.length in 1..128)
            "custom_rules" -> {
                val rules = JSONArray(value)
                require(rules.length() <= 50)
                (0 until rules.length()).forEach { TxtChapterRule.fromJson(rules.getJSONObject(it)) }
            }
            else -> TxtChapterRule.fromJson(JSONObject(value))
        }
    }.isSuccess

    fun applyFlutterPreferences(context: Context, document: JSONObject) {
        if (document.optInt("__prefsBackupVersion", -1) != 1) return
        val appearance = context.getSharedPreferences("native_reader_appearance", Context.MODE_PRIVATE)
        val narration = context.getSharedPreferences("native_narration", Context.MODE_PRIVATE)

        val style = document.optJSONObject("readStyle")
            ?.takeIf { it.optString("type") == "string" }
            ?.opt("value") as? String
        val lineHeight = try {
            style?.let { JSONObject(it).opt("lineHeight") as? Number }?.toDouble()
        } catch (_: Exception) { null }
        val theme = document.optJSONObject("readTheme")
            ?.takeIf { it.optString("type") == "string" }
            ?.opt("value") as? String
        val themeColors = try {
            theme?.let { JSONObject(it) }?.let { value ->
                val background = value.optString("backgroundColor")
                val foreground = value.optString("textColor")
                if (readerThemeColor(background) != null && readerThemeColor(foreground) != null)
                    background to foreground else null
            }
        } catch (_: Exception) { null }
        val appearanceEditor = appearance.edit()
        var appearanceChanged = false
        if (lineHeight != null && lineHeight.isFinite() && lineHeight in 1.2..2.2) {
            appearanceEditor.putFloat("line_spacing", lineHeight.toFloat())
            appearanceChanged = true
        }
        if (themeColors != null) {
            val palette = ReaderPalette.entries.firstOrNull {
                it != ReaderPalette.CUSTOM && it.background == readerThemeColor(themeColors.first) &&
                    it.foreground == readerThemeColor(themeColors.second)
            } ?: ReaderPalette.CUSTOM
            appearanceEditor.putString("palette", palette.name)
            appearanceEditor.putString("custom_background",
                if (palette == ReaderPalette.CUSTOM) themeColors.first.uppercase() else null)
            appearanceEditor.putString("custom_foreground",
                if (palette == ReaderPalette.CUSTOM) themeColors.second.uppercase() else null)
            appearanceChanged = true
        }
        val image = document.optJSONObject("bgimg")
            ?.takeIf { it.optString("type") == "string" }
            ?.optString("value")
        val fit = document.optJSONObject("bgimgFit")
            ?.takeIf { it.optString("type") == "string" }
            ?.optString("value")?.takeIf { it in setOf("cover", "stretch") } ?: "cover"
        val autoAdjust = document.optJSONObject("autoAdjustReadingTheme")
            ?.takeIf { it.optString("type") == "bool" }
            ?.opt("value") as? Boolean ?: false
        if (image != null) {
            val parsed = runCatching { ReaderBackgroundImage.parse(image, fit) }.getOrNull()
            if (parsed != null) {
                appearanceEditor.putString("background_image", parsed.copy(autoAdjust = autoAdjust).toPreference())
                appearanceEditor.putString("background_image_fit", fit)
                appearanceChanged = true
            } else if (runCatching { JSONObject(image).optString("type") }.getOrNull() == "none") {
                appearanceEditor.remove("background_image")
                appearanceChanged = true
            }
        }
        if (appearanceChanged && !appearanceEditor.commit()) {
            throw IOException("Cannot restore Flutter appearance settings")
        }
        document.optJSONObject("volumeKeyTurnPage")
            ?.takeIf { it.optString("type") == "bool" }
            ?.opt("value")?.let { value ->
                if (value is Boolean) ReaderControlsStore(context).setVolumeKeysTurnPages(value)
            }
        val sortField = document.optJSONObject("sortField")
            ?.takeIf { it.optString("type") == "string" }
            ?.optString("value")?.let { name -> BookSortField.entries.firstOrNull {
                it.name.equals(name, ignoreCase = true) ||
                    (it == BookSortField.IMPORTED && name == "importTime") ||
                    (it == BookSortField.LAST_READ && name == "lastReadTime")
            } }
        val descending = document.optJSONObject("sortOrder")
            ?.takeIf { it.optString("type") == "string" }
            ?.optString("value")?.let { when (it) {
                "ascending" -> false
                "descending" -> true
                else -> null
            } }
        if (sortField != null || descending != null) {
            val bookshelf = BookshelfSortStore(context)
            val current = bookshelf.load()
            bookshelf.save(BookshelfSort(sortField ?: current.field, descending ?: current.descending))
        }

        fun doublePreference(key: String): Double? {
            val entry = document.optJSONObject(key) ?: return null
            if (entry.optString("type") != "double") return null
            return (entry.opt("value") as? Number)?.toDouble()?.takeIf(Double::isFinite)
        }
        val flutterRate = doublePreference("ttsRate")
        val rate = flutterRate?.times(2)?.takeIf { it in 0.1..4.0 }
        val pitch = doublePreference("ttsPitch")?.takeIf { it in 0.5..2.0 }
        if (rate != null || pitch != null) {
            val editor = narration.edit()
            if (rate != null) editor.putFloat("rate", rate.toFloat())
            if (pitch != null) editor.putFloat("pitch", pitch.toFloat())
            if (!editor.commit()) throw IOException("Cannot restore Flutter narration settings")
        }
        TxtChapterRuleStore(context).importFlutterPreferences(document)
    }
}
