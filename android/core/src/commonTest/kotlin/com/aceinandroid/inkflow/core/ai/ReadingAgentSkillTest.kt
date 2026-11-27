package com.aceinandroid.inkflow.core.ai

import kotlin.test.*

class ReadingAgentSkillTest {
    private val original = ReadingSkill("custom.study", "Original", "Keep", "Existing instructions")
    private val replacement = original.copy(title = "Replacement", instructions = "New instructions")

    @Test fun canonicalCatalogContainsTheFivePlannedAgentsAndDisabledCoach() {
        val registry = ReadingAgentRegistry()
        assertEquals(listOf("agent.general", "agent.story", "agent.background", "agent.argument", "agent.language", "agent.coach"), registry.agents.map { it.id })
        assertEquals(5, registry.available.size)
        assertFalse(registry.find("agent.coach")!!.enabled)
        assertTrue(registry.find("agent.coach")!!.localCapabilities.isEmpty())
        assertEquals("reading.history", registry.find("agent.background")!!.defaultSkillId)
        assertEquals("reading.argument", registry.find("agent.argument")!!.defaultSkillId)
        registry.available.forEach { agent ->
            assertNotNull(ReadingSkillRegistry().find(agent.defaultSkillId))
            assertTrue(ReadingLocalCapability.VERIFIED_BOOK_CONTEXT in agent.localCapabilities)
        }
    }

    @Test fun oldAliasesResolveWithoutAddingDuplicateCatalogRows() {
        val registry = ReadingAgentRegistry()
        assertEquals(registry.find("agent.argument"), registry.find("agent.scholar"))
        assertEquals(registry.find("agent.general"), registry.find("agent.notes"))
        assertEquals("agent.argument", registry.resolve("agent.scholar").id)
        assertNull(registry.find("future.agent"))
        assertEquals("agent.general", registry.resolve("future.agent").id)
        assertEquals("agent.general", registry.resolve("agent.coach").id)
    }

    @Test fun bookAgentFallbackHonorsDeviceChoiceAndRejectsDisabledBookChoice() {
        val registry = ReadingAgentRegistry()
        assertEquals("agent.background", registry.resolveBookAgent("agent.background", "agent.language").id)
        assertEquals("agent.language", registry.resolveBookAgent(null, "agent.language").id)
        assertEquals("agent.language", registry.resolveBookAgent("agent.coach", "agent.language").id)
        assertEquals("agent.general", registry.resolveBookAgent("future.agent", "future.device").id)
        assertEquals("agent.argument", registry.resolveBookAgent("agent.scholar", null).id)
    }

    @Test fun bookSkillThenDeviceSkillThenRegisteredDefault() {
        val registry = ReadingAgentRegistry()
        val skills = ReadingSkillRegistry(listOf(original, replacement.copy(id = "custom.device")))
        assertEquals(original, registry.resolveSkill("agent.background", skills, "custom.study", "custom.device"))
        assertEquals("custom.device", registry.resolveSkill("agent.background", skills, "custom.deleted", "custom.device").id)
        assertEquals("reading.history", registry.resolveSkill("agent.background", skills, "future.skill", "custom.deleted").id)
        assertFailsWith<IllegalArgumentException> { registry.resolveSkill("agent.coach", skills) }
        assertFailsWith<IllegalArgumentException> { registry.resolveSkill("future.agent", skills) }
    }

    @Test fun previewsHaveNoMutationAndSameIdImportsCopyByDefault() {
        val existing = ReadingSkillRegistry(listOf(original))
        val preview = SkillImportPreview.create(listOf(replacement), existing)
        assertEquals(setOf(original.id), preview.conflictingIds)
        assertEquals(original, existing.find(original.id))
        val first = preview.merge(existing)
        assertEquals(original, first.first { it.id == original.id })
        assertEquals("custom.study.copy1", first.last().id)
        assertEquals(replacement.instructions, first.last().instructions)
        val second = preview.merge(ReadingSkillRegistry(first))
        assertEquals("custom.study.copy2", second.last().id)
    }

    @Test fun explicitOverwriteOnlyReplacesPreviewedCustomConflicts() {
        val other = original.copy(id = "custom.other")
        val existing = ReadingSkillRegistry(listOf(original, other))
        val preview = SkillImportPreview.create(listOf(replacement), existing)
        val merged = preview.merge(existing, overwrite = true)
        assertEquals(2, merged.size)
        assertEquals(replacement, merged.first { it.id == original.id })
        assertEquals(other, merged.first { it.id == other.id })
        // A conflict that appeared after preview must still create a copy.
        val stale = SkillImportPreview.create(listOf(replacement), ReadingSkillRegistry())
        assertEquals("custom.study.copy1", stale.merge(existing, overwrite = true).last().id)
    }

    @Test fun builtInsCanOnlyBeImportedAsCustomCopiesEvenWithOverwriteSelected() {
        val builtin = ReadingSkillRegistry.builtIns.first()
        val preview = SkillImportPreview.create(listOf(builtin.copy(title = "Edited")), ReadingSkillRegistry())
        val merged = preview.merge(ReadingSkillRegistry(), overwrite = true)
        assertEquals("custom.reading.general", merged.single().id)
        assertEquals(builtin, ReadingSkillRegistry(merged).find(builtin.id))
        assertEquals("Edited", merged.single().title)
    }

    @Test fun invalidBatchOrTooManySkillsNeverChangesExistingRegistry() {
        val existing = ReadingSkillRegistry(listOf(original))
        assertFailsWith<IllegalArgumentException> { SkillImportPreview.create(listOf(replacement, replacement), existing) }
        val invalid = replacement.copy(instructions = "x".repeat(16_001))
        assertFailsWith<IllegalArgumentException> { SkillImportPreview(listOf(replacement, invalid), emptySet()).merge(existing, true) }
        val full = ReadingSkillRegistry((1..SkillCodec.MAX_SKILLS).map { original.copy(id = "custom.$it") })
        val preview = SkillImportPreview.create(listOf(original), full)
        assertFailsWith<IllegalArgumentException> { preview.merge(full) }
        assertEquals(original, existing.find(original.id))
    }

    @Test fun copiesKeepBoundedIdsAndAllBuiltinMetadataReferencesAvailableAgents() {
        val longId = "custom." + "a".repeat(93)
        val custom = original.copy(id = longId)
        val existing = ReadingSkillRegistry(listOf(custom))
        val merged = SkillImportPreview.create(listOf(custom), existing).merge(existing)
        assertTrue(merged.last().id.length <= 100)
        ReadingSkillRegistry.builtIns.forEach { skill ->
            assertTrue(ReadingAgentRegistry().find(skill.recommendedAgent)?.enabled == true)
            assertTrue(skill.quickPrompts.isNotEmpty())
        }
    }
}
