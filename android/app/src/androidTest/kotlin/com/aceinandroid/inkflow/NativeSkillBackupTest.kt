package com.aceinandroid.inkflow

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aceinandroid.inkflow.ai.NativeAiStore
import com.aceinandroid.inkflow.core.ai.ReadingSkill
import com.aceinandroid.inkflow.core.ai.SkillCodec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NativeSkillBackupTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferenceFiles = mutableSetOf<String>()
    private val portableStores = setOf(
        "native_reader_appearance", "native_narration", "native_reader_positions",
        "native_reader_text_positions", "native_txt_rules", "native_bookshelf", "native_reading_skills",
    )
    private val hardwareKeys = listOf(
        "eink_mode_enabled", "eink_high_contrast", "eink_disable_animations", "eink_large_touch_targets",
    )

    private inner class LocalContext : ContextWrapper(base) {
        private val prefix = "skill-backup-${UUID.randomUUID()}"
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = "$prefix-$name"
            preferenceFiles += isolatedName
            return base.getSharedPreferences(isolatedName, mode)
        }
    }

    @After fun cleanUp() {
        preferenceFiles.forEach(base::deleteSharedPreferences)
    }

    private fun preferences(context: Context, name: String) =
        context.getSharedPreferences(name, Context.MODE_PRIVATE)

    // Only the legacy Skill definition key may be read from the private workspace.
    // Fail on enumeration, credentials/selection reads or any private workspace mutation.
    private fun portableOnly(context: Context): Context = object : ContextWrapper(context) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            if (name in portableStores) return context.getSharedPreferences(name, mode)
            check(name == "native_ai_workspace") { "Backup accessed private preferences: $name" }
            val workspace = context.getSharedPreferences(name, mode)
            return object : SharedPreferences by workspace {
                override fun getAll(): MutableMap<String, *> = error("Backup enumerated private workspace")
                override fun edit(): SharedPreferences.Editor = error("Backup mutated private workspace")
                override fun getString(key: String?, defValue: String?): String? {
                    check(key == "custom_skills") { "Backup read private setting: $key" }
                    return workspace.getString(key, defValue)
                }
            }
        }
    }

    private fun entry(type: String, value: Any) = JSONObject().put("type", type).put("value", value)

    // Deliberately independent of capture: fixtures describe the old on-disk contracts.
    private fun legacyBackup(version: Int): JSONObject = JSONObject().apply {
        put("version", version)
        put("native_reader_appearance", JSONObject().put("font_size", entry("int", 23)))
        put("native_narration", JSONObject().put("continuous", entry("bool", true)))
        put("native_reader_positions", JSONObject().put("42", entry("int", 7)))
        put("native_reader_text_positions", JSONObject().put("42:offset", entry("int", 19)))
        if (version >= 2) put("native_txt_rules", JSONObject())
        if (version >= 3) put("native_bookshelf", JSONObject().put("descending", entry("bool", true)))
        if (version >= 4) put("native_reading_skills", JSONObject().put("custom_skills", entry("string", "[]")))
    }

    private fun saveSkills(context: Context, skills: List<ReadingSkill>) {
        assertTrue(preferences(context, "native_reading_skills").edit()
            .putString("custom_skills", SkillCodec.encodeStored(skills)).commit())
    }

    private fun storedSkills(context: Context) = SkillCodec.decodeStored(
        preferences(context, "native_reading_skills").getString("custom_skills", "[]")!!,
    )

    private fun sampleSkill() = ReadingSkill(
        id = "custom.backup.metadata", title = "史料核查 日本語 한국어",
        summary = "区分证据与推断", instructions = "先引用原文。\n再解释 \"依据\" 与局限。",
        schemaVersion = SkillCodec.SCHEMA_VERSION, category = "history",
        recommendedAgent = "agent.background", quickPrompts = listOf("依据是什么？", "Explain \"context\"\n引用"),
        outputFormat = "json",
    )

    @Test fun v5RoundTripPreservesAllSkillMetadata() {
        val source = LocalContext()
        val target = LocalContext()
        val skill = sampleSkill()
        saveSkills(source, listOf(skill))
        val backup = JSONObject(NativeSettingsBackup.capture(portableOnly(source)).toString())
        assertEquals(5, backup.getInt("version"))
        val encoded = backup.getJSONObject("native_reading_skills").getJSONObject("custom_skills").getString("value")
        assertEquals(SkillCodec.SCHEMA_VERSION, JSONObject(encoded).getInt("schemaVersion"))
        saveSkills(target, listOf(skill.copy(id = "custom.replaced")))
        NativeSettingsBackup.apply(portableOnly(target), backup)
        assertEquals(listOf(skill), storedSkills(target))
        assertEquals(encoded, NativeSettingsBackup.capture(portableOnly(target))
            .getJSONObject("native_reading_skills").getJSONObject("custom_skills").getString("value"))
    }

    @Test fun v5CaptureRetainsLegacySkillFallbackWithoutExportingPrivateWorkspace() {
        val source = LocalContext()
        val target = LocalContext()
        val skill = sampleSkill()
        val legacy = preferences(source, "native_ai_workspace")
        assertTrue(legacy.edit().putString("custom_skills", SkillCodec.encodeStored(listOf(skill)))
            .putString("api_key", "private-credential-fixture")
            .putString("selected_agent", "agent.argument")
            .putString("book_skill_binding.42", skill.id).commit())
        assertFalse(preferences(source, "native_reading_skills").contains("custom_skills"))
        assertEquals(skill, NativeAiStore(source).skills().find(skill.id))
        val before = legacy.all.toMap()
        val backup = NativeSettingsBackup.capture(portableOnly(source))
        assertFalse(backup.toString().contains("private-credential-fixture"))
        assertFalse(backup.toString().contains("selected_agent"))
        assertFalse(backup.toString().contains("book_skill_binding"))
        NativeSettingsBackup.apply(portableOnly(target), backup)
        assertEquals(listOf(skill), storedSkills(target))
        assertEquals(skill, NativeAiStore(target).skills().find(skill.id))
        assertEquals(before, legacy.all)
        assertFalse(preferences(source, "native_reading_skills").contains("custom_skills"))

        // An explicit empty portable collection means the user removed the legacy Skill.
        saveSkills(source, emptyList())
        val empty = NativeSettingsBackup.capture(portableOnly(source))
        NativeSettingsBackup.apply(portableOnly(target), empty)
        assertTrue(storedSkills(target).isEmpty())
        assertNull(NativeAiStore(target).skills().find(skill.id))
    }

    @Test fun v4LegacyFourFieldArrayRestoresAndNextCaptureUpgrades() {
        val target = LocalContext()
        val old = JSONObject().put("id", "custom.legacy").put("title", "旧版方法")
            .put("summary", "旧摘要").put("instructions", "保留旧说明\n第二行")
        val backup = legacyBackup(4)
        backup.getJSONObject("native_reading_skills")
            .put("custom_skills", entry("string", JSONArray().put(old).toString()))
        NativeSettingsBackup.apply(portableOnly(target), backup)
        val restored = storedSkills(target).single()
        assertEquals("custom.legacy", restored.id)
        assertEquals("旧版方法", restored.title)
        assertEquals("旧摘要", restored.summary)
        assertEquals("保留旧说明\n第二行", restored.instructions)
        assertEquals("general", restored.category)
        assertEquals(emptyList<String>(), restored.quickPrompts)
        val upgraded = NativeSettingsBackup.capture(portableOnly(target))
        assertEquals(5, upgraded.getInt("version"))
        val encoded = upgraded.getJSONObject("native_reading_skills").getJSONObject("custom_skills").getString("value")
        assertEquals(SkillCodec.SCHEMA_VERSION, JSONObject(encoded).getInt("schemaVersion"))
        assertEquals(listOf(restored.copy(schemaVersion = SkillCodec.SCHEMA_VERSION)), SkillCodec.decodeStored(encoded))
    }

    @Test fun v1ThroughV3RestoreOnlyTheirVersionedSections() {
        for (version in 1..3) {
            val target = LocalContext()
            val skill = sampleSkill()
            saveSkills(target, listOf(skill))
            assertTrue(preferences(target, "native_txt_rules").edit().putString("custom_rules", "[]").commit())
            assertTrue(preferences(target, "native_bookshelf").edit().putBoolean("descending", false).commit())
            val backup = legacyBackup(version)
            // Extra newer sections are untrusted and must neither be validated nor applied.
            backup.put("native_reading_skills", JSONObject().put("custom_skills", entry("string", "not skills")))
            if (version < 2) backup.put("native_txt_rules", JSONObject().put("invalid", entry("bool", true)))
            if (version < 3) backup.put("native_bookshelf", JSONObject().put("invalid", entry("bool", true)))
            NativeSettingsBackup.apply(portableOnly(target), backup)
            assertEquals(23, preferences(target, "native_reader_appearance").getInt("font_size", 0))
            assertTrue(preferences(target, "native_narration").getBoolean("continuous", false))
            assertEquals(7, preferences(target, "native_reader_positions").getInt("42", 0))
            assertEquals(19, preferences(target, "native_reader_text_positions").getInt("42:offset", 0))
            assertEquals(version < 2, preferences(target, "native_txt_rules").contains("custom_rules"))
            assertEquals(version >= 3, preferences(target, "native_bookshelf").getBoolean("descending", false))
            assertEquals(listOf(skill), storedSkills(target))
        }
    }

    @Test fun capturePrefersPortableSkillsAndDoesNotMigrateOrExportPrivateWorkspace() {
        val source = LocalContext()
        val portableSkill = sampleSkill().copy(id = "custom.portable")
        saveSkills(source, listOf(portableSkill))
        val workspace = preferences(source, "native_ai_workspace")
        assertTrue(workspace.edit().putString("custom_skills", SkillCodec.encodeStored(listOf(sampleSkill())))
            .putString("selected_agent", "agent.background").putString("provider_secret", "private-token").commit())
        val before = workspace.all.toMap()
        val backup = NativeSettingsBackup.capture(portableOnly(source))
        assertEquals(listOf(portableSkill), SkillCodec.decodeStored(backup.getJSONObject("native_reading_skills")
            .getJSONObject("custom_skills").getString("value")))
        assertEquals(before, workspace.all)
        assertEquals(listOf(portableSkill), storedSkills(source))
        assertFalse(backup.toString().contains("private-token"))
    }

    @Test fun agentAndBookBindingsAndLanguageStayLocalDuringCaptureAndRestore() {
        val source = LocalContext()
        val target = LocalContext()
        saveSkills(source, listOf(sampleSkill()))
        saveSkills(target, listOf(sampleSkill().copy(id = "custom.before")))
        val sourceStore = NativeAiStore(source)
        sourceStore.selectAgent("agent.background")
        sourceStore.bindAgentSkill("agent.background", "reading.history")
        sourceStore.setAgentForBook(42, "agent.language")
        sourceStore.setSkillForBook(42, "reading.language")
        val targetStore = NativeAiStore(target)
        targetStore.selectAgent("agent.argument")
        targetStore.bindAgentSkill("agent.argument", "reading.critical")
        targetStore.setAgentForBook(42, "agent.story")
        targetStore.setSkillForBook(42, "reading.characters")
        // Also guard accidental local keys in a portable store from export/clearing.
        for (context in listOf(source, target)) {
            assertTrue(preferences(context, "native_reader_appearance").edit()
                .putString("device_language", if (context === source) "ja" else "ko")
                .putString("selected_agent", "local-only-selection")
                .putString("agent_skill_binding.agent.story", "local-only-skill")
                .putString("book_agent.99", "local-only-agent")
                .putString("book_skill_binding.99.agent.story", "local-only-book-skill").commit())
        }
        val workspaceBefore = preferences(target, "native_ai_workspace").all.toMap()
        val appearanceBefore = preferences(target, "native_reader_appearance").all.toMap()
        val backup = NativeSettingsBackup.capture(portableOnly(source))
        val text = backup.toString()
        for (key in listOf("native_ai_workspace", "device_language", "selected_agent", "agent_skill_binding.",
            "book_agent.", "book_skill_binding.")) assertFalse("Export leaked $key", text.contains(key))
        NativeSettingsBackup.apply(portableOnly(target), backup)
        assertEquals(workspaceBefore, preferences(target, "native_ai_workspace").all)
        assertEquals(appearanceBefore, preferences(target, "native_reader_appearance").all)
        assertEquals("agent.argument", targetStore.selectedAgent().id)
        assertEquals("reading.critical", targetStore.skillForAgent("agent.argument").id)
        assertEquals("agent.story", targetStore.agentForBook(42).id)
        assertEquals("reading.characters", targetStore.skillForBook(42).id)
        assertEquals(listOf(sampleSkill()), storedSkills(target))
    }

    @Test fun v5CaptureAndRestorePreserveHardwarePolicyIncludingAbsentKeys() {
        val source = LocalContext()
        val target = LocalContext()
        val emptyTarget = LocalContext()
        val sourceAppearance = preferences(source, "native_reader_appearance")
        val targetAppearance = preferences(target, "native_reader_appearance")
        val sourceEditor = sourceAppearance.edit().putInt("font_size", 26)
        val targetEditor = targetAppearance.edit().putInt("font_size", 17)
        hardwareKeys.forEachIndexed { index, key ->
            sourceEditor.putBoolean(key, index % 2 == 0)
            targetEditor.putBoolean(key, index % 2 != 0)
        }
        sourceEditor.putString("eink_future_policy", "source-device")
        targetEditor.putString("eink_future_policy", "target-device")
        assertTrue(sourceEditor.commit())
        assertTrue(targetEditor.commit())
        val before = targetAppearance.all.filterKeys { it.startsWith("eink") }
        val backup = NativeSettingsBackup.capture(portableOnly(source))
        assertFalse(backup.toString().contains("eink"))
        NativeSettingsBackup.apply(portableOnly(target), backup)
        assertEquals(26, targetAppearance.getInt("font_size", 0))
        assertEquals(before, targetAppearance.all.filterKeys { it.startsWith("eink") })
        NativeSettingsBackup.apply(portableOnly(emptyTarget), backup)
        assertFalse(preferences(emptyTarget, "native_reader_appearance").all.keys.any { it.startsWith("eink") })
    }

    @Test fun v1ThroughV4IgnoreOldHardwareFieldsWithoutRejectingValidSettings() {
        for (version in 1..4) {
            val target = LocalContext()
            val emptyTarget = LocalContext()
            val appearance = preferences(target, "native_reader_appearance")
            val editor = appearance.edit().putString("eink_future_policy", "local-device")
            hardwareKeys.forEachIndexed { index, key -> editor.putBoolean(key, index % 2 == 0) }
            assertTrue(editor.commit())
            val before = appearance.all.toMap()
            val backup = legacyBackup(version)
            hardwareKeys.forEachIndexed { index, key -> backup.getJSONObject("native_reader_appearance")
                .put(key, entry("bool", index % 2 != 0)) }
            // Ignored hardware values need not conform to the current typed preference schema.
            backup.getJSONObject("native_reader_appearance").put("eink_future_policy", "obsolete-format")
            NativeSettingsBackup.parse(backup)
            NativeSettingsBackup.apply(portableOnly(target), backup)
            assertEquals(23, appearance.getInt("font_size", 0))
            assertEquals(before, appearance.all.filterKeys { it.startsWith("eink") })
            NativeSettingsBackup.apply(portableOnly(emptyTarget), backup)
            assertFalse(preferences(emptyTarget, "native_reader_appearance").all.keys.any { it.startsWith("eink") })
        }
    }

    @Test fun v5HardwareInjectionIsRejectedBeforeAnyWrites() {
        val target = LocalContext()
        val appearance = preferences(target, "native_reader_appearance")
        assertTrue(appearance.edit().putInt("font_size", 18).putBoolean("eink_mode_enabled", true).commit())
        val before = appearance.all.toMap()
        val backup = legacyBackup(5)
        backup.getJSONObject("native_reader_appearance").put("eink_mode_enabled", entry("bool", false))
        assertThrows(IOException::class.java) { NativeSettingsBackup.apply(portableOnly(target), backup) }
        assertEquals(before, appearance.all)
    }

    @Test fun invalidSkillsAreRejectedBeforeEarlierPreferenceSectionsAreWritten() {
        val target = LocalContext()
        assertTrue(preferences(target, "native_reader_appearance").edit().putInt("font_size", 18).commit())
        saveSkills(target, listOf(sampleSkill()))
        val backup = legacyBackup(5)
        backup.getJSONObject("native_reading_skills").put("custom_skills", entry("string", "not skills"))
        assertThrows(IOException::class.java) { NativeSettingsBackup.apply(portableOnly(target), backup) }
        assertEquals(18, preferences(target, "native_reader_appearance").getInt("font_size", 0))
        assertEquals(listOf(sampleSkill()), storedSkills(target))
    }
}
