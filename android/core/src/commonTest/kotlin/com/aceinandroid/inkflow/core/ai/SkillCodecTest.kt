package com.aceinandroid.inkflow.core.ai

import kotlin.test.*

class SkillCodecTest {
    private val legacy = """{"id":"custom.close","title":"Close","summary":"Summary","instructions":"Use evidence"}"""
    private val skill = ReadingSkill("custom.rich", "引用 \"证据\" 📖", "Summary\nSecond line", "Read carefully.\n\nKeep exact quotes.\n",
        category = "study", recommendedAgent = "agent.argument", quickPrompts = listOf("Why, exactly?", "What is 'known'?", "比较观点 📖"), outputFormat = "json")

    @Test fun oldFourFieldObjectRemainsReadableAndUpgradesOnExport() {
        val decoded = SkillCodec.decode(legacy).single()
        assertEquals(1, decoded.schemaVersion)
        assertEquals("general", decoded.category)
        assertEquals("markdown", decoded.outputFormat)
        assertNull(decoded.recommendedAgent)
        assertTrue(decoded.quickPrompts.isEmpty())
        assertEquals(decoded.copy(schemaVersion = SkillCodec.SCHEMA_VERSION), SkillCodec.decode(SkillCodec.encodeJson(decoded)).single())
    }

    @Test fun legacyUnicodeIdsAndLongTitlesAndSummariesRemainPortable() {
        val legacySkill = ReadingSkill("custom.读书", "标题".repeat(150), "简介".repeat(1200), "Evidence", schemaVersion = 1)
        val decoded = SkillCodec.decode(SkillCodec.encodeJson(legacySkill)).single()
        assertEquals(legacySkill.copy(schemaVersion = SkillCodec.SCHEMA_VERSION), decoded)
        assertEquals(decoded, SkillCodec.decodeStored(SkillCodec.encodeStored(listOf(decoded))).single())
    }

    @Test fun jsonAndMarkdownRoundTripAllMetadataAndInstructionWhitespace() {
        assertEquals(skill, SkillCodec.decode(SkillCodec.encodeJson(skill)).single())
        assertEquals(skill, SkillCodec.decode(SkillCodec.encodeMarkdown(skill)).single())
        assertEquals(listOf(skill, skill.copy(id = "custom.two")),
            SkillCodec.decode(SkillCodec.encodeJson(listOf(skill, skill.copy(id = "custom.two")))))
    }

    @Test fun versionedCollectionAcceptsLegacyMembersButRejectsUnknownVersions() {
        assertEquals("custom.close", SkillCodec.decode("""{"version":2,"skills":[$legacy]}""").single().id)
        listOf(
            """{"version":3,"skills":[$legacy]}""",
            legacy.replace("\"id\":", "\"schemaVersion\":3,\"id\":"),
            """{"skills":[$legacy]}""",
            """{"schemaVersion":2,"version":2,"skills":[$legacy]}""",
            """{"schemaVersion":null,"skills":[$legacy]}""",
            """{"schemaVersion":2.0,"skills":[$legacy]}""",
            """{"schemaVersion":2,"skills":[]}""",
        ).forEach { source -> assertFailsWith<IllegalArgumentException>(source) { SkillCodec.decode(source) } }
    }

    @Test fun fileLimitIsExactly64KiBOfUtf8AndNotCharacterCount() {
        val boundary = legacy.padEnd(SkillCodec.MAX_FILE_BYTES, ' ')
        assertEquals(1, SkillCodec.decode(boundary.encodeToByteArray()).size)
        assertFailsWith<IllegalArgumentException> { SkillCodec.decode((boundary + " ").encodeToByteArray()) }
        // The string is under 64K characters but over 64K UTF-8 bytes.
        val unicode = SkillCodec.encodeJson(skill.copy(instructions = "中".repeat(16_000)))
        val collection = """{"schemaVersion":2,"skills":[$unicode,${unicode.replace("custom.rich", "custom.two")}]}"""
        assertTrue(collection.length < SkillCodec.MAX_FILE_BYTES)
        assertTrue(collection.encodeToByteArray().size > SkillCodec.MAX_FILE_BYTES)
        assertFailsWith<IllegalArgumentException> { SkillCodec.decode(collection) }
    }

    @Test fun invalidUtf8AndUnpairedSurrogatesFailInsteadOfBeingReplaced() {
        assertFailsWith<Exception> { SkillCodec.decode(byteArrayOf(0xC3.toByte(), 0x28)) }
        assertFailsWith<Exception> { SkillCodec.decode(legacy.replace("Use evidence", "\\ud800")) }
        assertFailsWith<Exception> { SkillCodec.encodeJson(skill.copy(title = "bad\uD800")) }
    }

    @Test fun instructionsAreCappedAt16000AcrossBothFormatsAndDirectRegistryUse() {
        val allowed = skill.copy(instructions = "x".repeat(16_000))
        assertEquals(16_000, SkillCodec.decode(SkillCodec.encodeMarkdown(allowed)).single().instructions.length)
        val tooLong = skill.copy(instructions = "x".repeat(16_001))
        assertFailsWith<IllegalArgumentException> { ReadingSkillRegistry(listOf(tooLong)) }
        assertFailsWith<IllegalArgumentException> { SkillCodec.decode(legacy.replace("Use evidence", tooLong.instructions)) }
        assertFailsWith<IllegalArgumentException> { SkillCodec.decode(SkillCodec.encodeMarkdown(allowed) + "x") }
    }

    @Test fun markdownSupportsOnlyScalarAndScalarListFrontmatter() {
        val source = """---
            |schemaVersion: 2
            |id: custom.markdown
            |title: 'Reader''s method'
            |summary: Plain summary
            |category: study
            |recommendedAgent: agent.argument
            |quickPrompts:
            |  - 'First question?'
            |  - "Second question?"
            |outputFormat: text
            |---
            |Instructions with !tags and &anchors are ordinary body text.
        """.trimMargin().trimEnd()
        val decoded = SkillCodec.decode(source).single()
        assertEquals("Reader's method", decoded.title)
        assertEquals(listOf("First question?", "Second question?"), decoded.quickPrompts)
        assertTrue("!tags" in decoded.instructions)
        assertEquals(listOf("One", "Two, with comma", "Three"), SkillCodec.decode(source.replace(
            "quickPrompts:\n  - 'First question?'\n  - \"Second question?\"", "quickPrompts: [One, \"Two, with comma\", 'Three']")).single().quickPrompts)
    }

    @Test fun markdownRejectsYamlExecutionAndAmbiguousMetadata() {
        val base = "---\nid: custom.safe\ntitle: Safe\nsummary: Summary\n%s\n---\nInstructions"
        listOf(
            "title: Duplicate", "tools: [shell]", "provider: secret", "<<: *defaults",
            "category: !!java/object Thing", "category: &anchor study", "category: *alias",
            "category: |\n  folded", "category: {nested: object}",
            "quickPrompts: [!tag bad]", "quickPrompts:\n  - &anchor bad",
            "quickPrompts:\n  - *alias", "quickPrompts:\n  - {key: value}",
            "  title: nested", "%YAML 1.2", "category:\tstudy", "unknown: harmless",
        ).forEach { field -> assertFailsWith<IllegalArgumentException>(field) { SkillCodec.decode(base.replace("%s", field)) } }
        assertFailsWith<IllegalArgumentException> { SkillCodec.decode("---\nid: custom.unclosed") }
    }

    @Test fun strictJsonRejectsDuplicatesUnknownFieldsTrailingContentAndNestedPayloads() {
        listOf(
            legacy.replace("\"title\":", "\"id\":\"custom.second\",\"title\":"),
            legacy.dropLast(1) + ",\"tools\":[\"shell\"]}", legacy + legacy,
            legacy.dropLast(1) + ",}", "[$legacy]",
            """{"schemaVersion":2,"skills":[$legacy,$legacy]}""",
            legacy.replace("\"Use evidence\"", "[[[[[[[[[\"deep\"]]]]]]]]]"),
        ).forEach { source -> assertFailsWith<IllegalArgumentException>(source) { SkillCodec.decode(source) } }
    }

    @Test fun legacyPreferenceArraysAndNewStoragePreserveMultipleImportsBeyondFileLimit() {
        val first = skill.copy(instructions = "中".repeat(16_000))
        val second = first.copy(id = "custom.second")
        val stored = SkillCodec.encodeStored(listOf(first, second))
        assertTrue(stored.encodeToByteArray().size > SkillCodec.MAX_FILE_BYTES)
        assertEquals(listOf(first, second), SkillCodec.decodeStored(stored))
        assertEquals("custom.close", SkillCodec.decodeStored("[$legacy]").single().id)
        assertTrue(SkillCodec.decodeStored("[]").isEmpty())
        assertTrue(SkillCodec.decodeStored(SkillCodec.encodeStored(emptyList())).isEmpty())
        assertFailsWith<IllegalArgumentException> { SkillCodec.decodeStored("[${SkillCodec.encodeJson(ReadingSkillRegistry.builtIns.first())}]") }
    }

    @Test fun metadataLimitsAndInvalidIdsAreValidatedBeforePersistence() {
        listOf(skill.copy(id = "../escape"), skill.copy(title = " "), skill.copy(category = "bad/category"),
            skill.copy(quickPrompts = List(13) { "prompt" }), skill.copy(quickPrompts = listOf("")),
            skill.copy(recommendedAgent = "../shell"), skill.copy(outputFormat = "executable"),
            skill.copy(instructions = "bad\u0000body"), skill.copy(category = "bad category")
        ).forEach { invalid -> assertFailsWith<IllegalArgumentException> { ReadingSkillRegistry(listOf(invalid)) } }
        val futureAgent = skill.copy(recommendedAgent = "agent.future")
        assertEquals(futureAgent, SkillCodec.decode(SkillCodec.encodeJson(futureAgent)).single())
    }
}
