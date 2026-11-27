package com.aceinandroid.inkflow

import com.aceinandroid.inkflow.core.ai.ReadingSkill
import com.aceinandroid.inkflow.core.ai.ReadingSkillRegistry
import com.aceinandroid.inkflow.core.ai.SkillCodec
import org.junit.Assert.*
import org.junit.Test

class NativeSkillSyncControllerTest {
    private val skill = ReadingSkill("custom.review", "Review", "Summary", "Method")
    private class Fixture(initial: List<ReadingSkill>) {
        var skills = initial
        var writes = 0
        var failSourceWrites = false
        var failAfterApply = false
        var beforeApply: (() -> Unit)? = null
        val receipts = mutableSetOf<String>()
        private val revisions = mutableMapOf<String, Pair<String, Long>>()
        private val sources = mutableMapOf<String, String>()
        var controller = controller()
            private set

        fun restart() { controller = controller() }

        private fun controller() = NativeSkillSyncController(
            { SkillCodec.encodeStored(skills) },
            { preview ->
                beforeApply?.invoke()
                writes++
                skills = preview.merge(ReadingSkillRegistry(skills))
                check(!failAfterApply) { "Interrupted after import commit" }
            },
            { id, digest ->
                val old = revisions[id]
                (if (old?.first == digest) old.second else (old?.second ?: 0L) + 1L).also {
                    revisions[id] = digest to it
                }
            }, { it in receipts }, { receipts += it; Unit },
            { sources[it] }, { digest, id ->
                check(!failSourceWrites) { "Cannot save source identity" }
                sources[digest] = id
            })
    }

    /** Each device receives the other devices' previous snapshots, then publishes on the next round. */
    private fun roundTrip(devices: List<Fixture>) {
        val snapshots = devices.map { device ->
            device.controller.capture(NativeSkillSyncController.BOOK_KEY, 0L)
                .mapNotNull(device.controller::definition)
        }
        devices.forEachIndexed { index, device ->
            snapshots.forEachIndexed { source, definitions ->
                if (source != index) device.controller.merge(definitions)
            }
        }
    }

    private fun assertDefinitions(expected: List<ReadingSkill>, device: Fixture) {
        assertEquals(expected.size, device.skills.size)
        assertEquals(expected.map { it.copy(id = "custom.definition") }.toSet(),
            device.skills.map { it.copy(id = "custom.definition") }.toSet())
    }

    @Test fun conflictingDevicesAndLateJoinerConvergeAcrossRepeatedRoundTripsAndRestarts() {
        val first = skill.copy(instructions = "Device A")
        val second = skill.copy(instructions = "Device B")
        val devices = mutableListOf(Fixture(listOf(first)), Fixture(listOf(second)))
        repeat(20) { round ->
            if (round == 5) devices += Fixture(emptyList())
            roundTrip(devices)
            devices.forEach { device ->
                assertDefinitions(listOf(first, second), device)
                device.restart() // Private sync identity persists; controller memory does not.
            }
        }
        devices.forEach { device ->
            val exported = device.controller.capture(NativeSkillSyncController.BOOK_KEY, 0L)
                .mapNotNull(device.controller::definition)
            assertEquals(setOf(first, second), exported.toSet())
            assertEquals(2, exported.size)
        }
    }

    @Test fun sameInstructionsWithDistinctMetadataSurviveConflictFamilyDeduplication() {
        val variants = listOf(skill, skill.copy(category = "study"),
            skill.copy(quickPrompts = listOf("Different prompt"), outputFormat = "json"))
        val devices = variants.map { Fixture(listOf(it)) }
        repeat(12) {
            roundTrip(devices)
            devices.forEach { device ->
                assertDefinitions(variants, device)
                device.restart()
            }
        }
    }

    @Test fun unrelatedIdsWithIdenticalContentAndCopyLikeNamesRemainDistinct() {
        val variants = listOf(skill, skill.copy(id = "custom.other"),
            skill.copy(id = "custom.review.copy1"), skill.copy(id = "custom.review.copycat"))
        val devices = variants.map { Fixture(listOf(it)) }
        repeat(12) {
            roundTrip(devices)
            devices.forEach { device ->
                assertEquals(variants.toSet(), device.skills.toSet())
                assertEquals(4, device.skills.size)
                device.restart()
            }
        }
    }

    @Test fun longSourceIdsSharingTruncatedCopyPrefixKeepTheirSeparateDefinitions() {
        val prefix = "custom." + "a".repeat(81)
        val first = skill.copy(id = prefix + "first", instructions = "First definition")
        val second = first.copy(id = prefix + "second")
        val conflicting = first.copy(instructions = "Conflicting definition")
        val devices = listOf(Fixture(listOf(first)), Fixture(listOf(conflicting, second)))
        repeat(12) {
            roundTrip(devices)
            devices.forEach { device ->
                val exported = device.controller.capture(NativeSkillSyncController.BOOK_KEY, 0L)
                    .mapNotNull(device.controller::definition)
                assertEquals(3, device.skills.size)
                assertEquals(listOf(first, second, conflicting).toSet(), exported.toSet())
                assertEquals(3, exported.size)
                device.restart()
            }
        }
    }

    @Test fun editedConflictCopyPublishesDistinctContentWithoutStartingAnotherCopyChain() {
        val first = skill.copy(instructions = "Device A")
        val second = skill.copy(instructions = "Device B")
        val devices = listOf(Fixture(listOf(first)), Fixture(listOf(second)))
        roundTrip(devices)
        val edited = devices.first().skills.single { it.instructions == second.instructions }
            .copy(instructions = "Edited conflict copy")
        devices.first().skills = devices.first().skills.map { if (it.id == edited.id) edited else it }
        repeat(12) {
            roundTrip(devices)
            devices.forEach { device ->
                // Local edits intentionally suppress replay of the version this device replaced.
                assertDefinitions(if (device === devices.first()) listOf(first, edited)
                    else listOf(first, second, edited), device)
                device.restart()
            }
        }
    }

    @Test fun failedSourceIdentityCommitPreventsImportAndCanRetryAfterRestart() {
        val local = skill.copy(instructions = "Keep local")
        val device = Fixture(listOf(local))
        device.failSourceWrites = true
        try {
            device.controller.merge(listOf(skill))
            fail("Failed provenance commit must leave the definition unacknowledged")
        } catch (_: IllegalStateException) { }
        assertEquals(0, device.writes)
        assertEquals(listOf(local), device.skills)
        device.failSourceWrites = false
        device.restart()
        device.controller.merge(listOf(skill))
        assertEquals(1, device.writes)
        device.restart()
        val exported = device.controller.capture(NativeSkillSyncController.BOOK_KEY, 0L)
            .mapNotNull(device.controller::definition)
        assertEquals(setOf(local, skill), exported.toSet())
        assertEquals(2, exported.size)
    }

    @Test fun interruptedCommittedImportKeepsSourceForOrdinaryAndTruncatedCopyIds() {
        for (incoming in listOf(skill, skill.copy(id = "custom." + "a".repeat(93)))) {
            val local = incoming.copy(instructions = "Keep local")
            val device = Fixture(listOf(local))
            device.failAfterApply = true
            try {
                device.controller.merge(listOf(incoming))
                fail("Interrupted import must not be acknowledged")
            } catch (_: IllegalStateException) { }
            assertEquals(1, device.writes)
            device.failAfterApply = false
            device.restart()
            device.controller.merge(listOf(incoming))
            assertEquals(1, device.writes)
            device.restart()
            val exported = device.controller.capture(NativeSkillSyncController.BOOK_KEY, 0L)
                .mapNotNull(device.controller::definition)
            assertEquals(setOf(local, incoming), exported.toSet())
            assertEquals(2, exported.size)
        }
    }

    @Test fun concurrentImportReallocationPersistsTheActualCopySourceWithoutAliasingCollision() {
        val local = skill.copy(instructions = "Keep local")
        val collision = skill.copy(id = "custom.review.copy1", instructions = "Concurrent definition")
        val device = Fixture(listOf(local))
        device.beforeApply = { device.skills = device.skills + collision }
        device.controller.merge(listOf(skill))
        assertEquals(skill.instructions, device.skills.single { it.id == "custom.review.copy2" }.instructions)
        device.beforeApply = null
        device.restart()
        val exported = device.controller.capture(NativeSkillSyncController.BOOK_KEY, 0L)
            .mapNotNull(device.controller::definition)
        assertEquals(setOf(local, collision, skill), exported.toSet())
        assertEquals(3, exported.size)
        device.receipts.clear()
        device.controller.merge(listOf(skill))
        assertEquals(1, device.writes)
    }

    @Test fun capturesOnlyCustomDefinitionsWithStableIdentityAndClockWithoutBindingsOrContext() {
        val fixture = Fixture(listOf(skill))
        val first = fixture.controller.capture(NativeSkillSyncController.BOOK_KEY, 0L).single()
        assertEquals(first, fixture.controller.capture(NativeSkillSyncController.BOOK_KEY, 0L).single())
        assertEquals(NativeSkillSyncController.KIND, first["artifact_kind"])
        assertEquals(0L, first["book_id"])
        assertEquals(skill, fixture.controller.definition(first))
        assertFalse(first.keys.any { "binding" in it || "provider" in it || "authorization" in it })
        assertEquals("", first["source_text_snapshot"])
        assertEquals(0.0, first["source_progress"])
        fixture.skills = listOf(skill.copy(instructions = "Edited"))
        val second = fixture.controller.capture(NativeSkillSyncController.BOOK_KEY, 0L).single()
        assertEquals(first["id"], second["id"])
        assertTrue((second["updated_at"] as Long) > (first["updated_at"] as Long))
        assertEquals(0, fixture.writes)
    }

    @Test fun retryAndMultipleBranchesImportOnceAndConflictDefaultsToCopy() {
        val local = skill.copy(instructions = "Local")
        val fixture = Fixture(listOf(local))
        fixture.controller.merge(listOf(skill, skill))
        fixture.controller.merge(listOf(skill))
        assertEquals(1, fixture.writes)
        assertEquals(local, fixture.skills.first { it.id == skill.id })
        assertEquals(skill.instructions, fixture.skills.first { it.id == "custom.review.copy1" }.instructions)
        fixture.receipts.clear() // Restoring portable definitions does not restore private receipts.
        fixture.controller.merge(listOf(skill))
        assertEquals(1, fixture.writes)
        fixture.skills = listOf(local) // An explicit local removal is not undone by a remote retry.
        fixture.controller.merge(listOf(skill))
        assertEquals(listOf(local), fixture.skills)
    }

    @Test fun staleRemoteSnapshotDoesNotResurrectLocallyRemovedPublishedDefinition() {
        val fixture = Fixture(listOf(skill))
        fixture.controller.capture(NativeSkillSyncController.BOOK_KEY, 0L)
        fixture.skills = emptyList()
        fixture.controller.merge(listOf(skill))
        assertTrue(fixture.skills.isEmpty())
        assertEquals(0, fixture.writes)
    }

    @Test fun identicalDefinitionDoesNotCreateCopyAndUnknownVersionOrKindCannotImport() {
        val fixture = Fixture(listOf(skill))
        fixture.controller.merge(listOf(skill))
        assertEquals(0, fixture.writes)
        val row = fixture.controller.capture(NativeSkillSyncController.BOOK_KEY, 0L).single()
        assertNull(fixture.controller.definition(row + ("schema_version" to 99L)))
        assertNull(fixture.controller.definition(row + ("schema_version" to 2.5)))
        assertNull(fixture.controller.definition(row + ("artifact_kind" to "future.kind")))
        assertNull(fixture.controller.definition(row + ("module_id" to "future.module")))
        try {
            fixture.controller.definition(row + ("payload_json" to "{}"))
            fail("Malformed known definition accepted")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0, fixture.writes)
    }

    @Test fun opaqueCanonicalAndCompatibilityIdsCannotSuppressLocalBackups() {
        val fixture = Fixture(listOf(skill))
        val canonical = fixture.controller.capture(NativeSkillSyncController.BOOK_KEY, 0L).single()
        val canonicalId = canonical["id"] as String
        val first = fixture.controller.capture(NativeSkillSyncController.BOOK_KEY, 0L, setOf(canonicalId)).single()
        assertNotEquals(canonicalId, first["id"])
        assertEquals(skill, fixture.controller.definition(first))
        assertEquals(first, fixture.controller.capture(NativeSkillSyncController.BOOK_KEY, 0L, setOf(canonicalId)).single())
        val reserved = setOf(canonicalId, first["id"] as String)
        val second = fixture.controller.capture(NativeSkillSyncController.BOOK_KEY, 0L, reserved).single()
        assertFalse(second["id"] in reserved)
        assertEquals(second, fixture.controller.capture(NativeSkillSyncController.BOOK_KEY, 0L, reserved.reversed().toSet()).single())
        val edited = skill.copy(instructions = "Latest local edit must be backed up")
        fixture.skills = listOf(edited)
        val latest = fixture.controller.capture(NativeSkillSyncController.BOOK_KEY, 0L, reserved).single()
        assertEquals(second["id"], latest["id"])
        assertEquals(edited, fixture.controller.definition(latest))
        assertTrue((latest["updated_at"] as Long) > (second["updated_at"] as Long))
        assertEquals(0, fixture.writes)
    }

    @Test fun compatibilityRecordStillRestoresViaNormalConflictCopyPolicyOnlyOnce() {
        val source = Fixture(listOf(skill))
        val canonical = source.controller.capture(NativeSkillSyncController.BOOK_KEY, 0L).single()
        val opaque = canonical + mapOf("schema_version" to 99L, "payload_json" to "{\"future\":true}")
        val backup = source.controller.capture(NativeSkillSyncController.BOOK_KEY, 0L, setOf(opaque["id"] as String)).single()
        assertNull(source.controller.definition(opaque))
        val local = skill.copy(instructions = "Keep this device's edit")
        val receiver = Fixture(listOf(local))
        repeat(2) { receiver.controller.merge(listOfNotNull(receiver.controller.definition(opaque), receiver.controller.definition(backup))) }
        assertEquals(local, receiver.skills.single { it.id == skill.id })
        assertEquals(skill.instructions, receiver.skills.single { it.id == "custom.review.copy1" }.instructions)
        assertEquals(1, receiver.writes)
    }
}
