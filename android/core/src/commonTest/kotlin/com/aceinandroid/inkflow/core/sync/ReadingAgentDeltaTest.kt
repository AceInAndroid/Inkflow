package com.aceinandroid.inkflow.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadingAgentDeltaTest {
    private fun table(name: String) = ReadingAgentDelta.tables.first { it.name == name }
    private fun row(vararg pairs: Pair<String, Any?>): DeltaRow = mapOf("id" to "record", "book_id" to 7L,
        "updated_at" to 100L) + pairs

    @Test fun crossDeviceBookIdsUseHashAndRemap() {
        val hash = "ABCDEF0123456789ABCDEF0123456789"
        assertEquals(hash.lowercase(), ReadingAgentDelta.bookKey(" $hash "))
        assertEquals(42L, ReadingAgentDelta.remap(row("book_id" to 999L), 42)["book_id"])
        assertNull(ReadingAgentDelta.bookKey("id-7"))
        assertNull(ReadingAgentDelta.bookKey(null))
    }

    @Test fun globalIdCollisionCannotMoveRowsBetweenBooks() {
        val decision = ReadingAgentDelta.merge(table("tb_reading_memory_documents"),
            row("book_id" to 99L, "updated_at" to 500L), row(), "local")
        assertNull(decision.row)
        assertTrue(decision.conflict)
    }

    @Test fun newerPositionKeepsExactProgressRatherThanFarthest() {
        val decision = ReadingAgentDelta.merge(table("tb_book_device_positions"),
            row("device_id" to "other", "progress" to 0.2, "updated_at" to 200L),
            row("device_id" to "other", "progress" to 0.8), "local")
        assertEquals(0.2, decision.row!!["progress"])
    }

    @Test fun remoteCannotReplaceLocalDevicePosition() {
        assertNull(ReadingAgentDelta.merge(table("tb_book_device_positions"),
            row("device_id" to "local", "updated_at" to 500L), row(), "local").row)
    }

    @Test fun staleAndEqualConflictsRetainLocalPayload() {
        val spec = table("tb_reading_memory_documents")
        assertNull(ReadingAgentDelta.merge(spec, row("updated_at" to 99L), row(), "local").row)
        val equal = ReadingAgentDelta.merge(spec, row("markdown" to "remote"), row("markdown" to "mine"), "local")
        assertNull(equal.row)
        assertTrue(equal.conflict)
        assertFalse(ReadingAgentDelta.merge(spec, row(), row(), "local").conflict)
    }

    @Test fun equalLifecycleStatesPreferTerminal() {
        val decision = ReadingAgentDelta.merge(table("tb_reading_checkpoints"), row("status" to "completed"), row("status" to "pending"), "local")
        assertEquals("completed", decision.row!!["status"])
    }

    @Test fun checkpointNaturalIdentityKeepsLocalId() {
        val decision = ReadingAgentDelta.merge(table("tb_reading_checkpoints"),
            row("id" to "remote", "updated_at" to 200L), row("id" to "local"), "local")
        assertEquals("local", decision.row!!["id"])
    }

    @Test fun staleArtifactCanOnlyTightenSpoilerVisibility() {
        val current = row("visible_from_progress" to 0.2, "source_progress" to 0.1, "payload_json" to "mine")
        val incoming = row("visible_from_progress" to 0.8, "source_progress" to 0.7, "payload_json" to "old", "updated_at" to 99L)
        val result = ReadingAgentDelta.merge(table("tb_reading_artifacts"), incoming, current, "local").row!!
        assertEquals(0.8, result["visible_from_progress"])
        assertEquals("mine", result["payload_json"])
        assertEquals(0.1, result["source_progress"])
    }

    @Test fun newerArtifactCannotLowerSpoilerVisibility() {
        val decision = ReadingAgentDelta.merge(table("tb_reading_artifacts"),
            row("visible_from_progress" to 0.1, "updated_at" to 200L), row("visible_from_progress" to 0.8), "local")
        assertEquals(0.8, decision.row!!["visible_from_progress"])
    }

    @Test fun wikiCorrectionBeatsNewerGeneratedVersion() {
        val current = row("user_corrected" to 1L, "version" to 2L, "visible_from_progress" to 0.5)
        val generated = row("user_corrected" to 0L, "version" to 99L, "updated_at" to 999L, "visible_from_progress" to 0.1)
        assertNull(ReadingAgentDelta.merge(table("tb_book_wiki_entries"), generated, current, "local").row)
        assertEquals(1L, ReadingAgentDelta.merge(table("tb_book_wiki_entries"), current, generated, "local").row!!["user_corrected"])
    }

    @Test fun coverageUnionsRangesWithoutExpandingLocalSafeBoundary() {
        val result = ReadingAgentDelta.merge(table("tb_book_reading_coverage"),
            row("safe_knowledge_boundary" to 0.9, "artifact_coverage_start" to 0.1,
                "artifact_coverage_end" to 0.9, "setup_status" to "pending", "initialized_at_progress" to 0.9, "updated_at" to 200L),
            row("safe_knowledge_boundary" to 0.3, "artifact_coverage_start" to 0.2,
                "artifact_coverage_end" to 0.4, "setup_status" to "fromHere", "initialized_at_progress" to 0.2), "local").row!!
        assertEquals(0.3, result["safe_knowledge_boundary"])
        assertEquals(0.1, result["artifact_coverage_start"])
        assertEquals(0.9, result["artifact_coverage_end"])
        assertEquals("fromHere", result["setup_status"])
        assertEquals(0.2, result["initialized_at_progress"])
    }

    @Test fun importedCoverageDoesNotGrantNewDeviceFullBookKnowledge() {
        val result = ReadingAgentDelta.merge(table("tb_book_wikis"), row("safe_knowledge_boundary" to 1.0), null, "local")
        assertEquals(0.0, result.row!!["safe_knowledge_boundary"])
    }

    @Test fun pinnedProfileWinsOverAutomaticClassification() {
        assertNull(ReadingAgentDelta.merge(table("tb_book_reading_profiles"),
            row("pinned" to 0L, "updated_at" to 200L), row("pinned" to 1L), "local").row)
    }

    @Test fun tombstonePreventsEqualOrOlderResurrection() {
        assertTrue(ReadingAgentDelta.deleted(row(), 100))
        assertTrue(ReadingAgentDelta.deleted(row(), 101))
        assertFalse(ReadingAgentDelta.deleted(row(), 99))
        assertFalse(ReadingAgentDelta.deleted(row(), 0))
        assertNull(ReadingAgentDelta.merge(table("tb_reading_sync_tombstones"),
            row("deleted_at" to 99L), row("deleted_at" to 100L), "local").row)
    }

    @Test fun unknownKindAndOpaquePayloadRoundTripUntouched() {
        val incoming = row("artifact_kind" to "future.new-kind", "payload_json" to "{\"future\":true}", "visible_from_progress" to 0.7)
        val result = ReadingAgentDelta.merge(table("tb_reading_artifacts"), incoming, null, "local").row!!
        assertEquals("future.new-kind", result["artifact_kind"])
        assertEquals(incoming["payload_json"], result["payload_json"])
    }

    @Test fun vocabularyUsesWordNamespaceAndPreservesLocalRowId() {
        assertEquals("und:example", VocabularyDelta.identity("example", "und"))
        assertFailsWith<IllegalArgumentException> { VocabularyDelta.identity("example", "en") }
        val result = VocabularyDelta.merge(row("id" to "remote", "definition_cn" to "new"), row("id" to "local"), 200, 100)
        assertEquals("local", result.row!!["id"])
        assertEquals("new", result.row!!["definition_cn"])
        assertTrue(VocabularyDelta.merge(row("definition_cn" to "remote"), row("definition_cn" to "mine"), 100, 100).conflict)
        assertNull(VocabularyDelta.merge(row(), row(), 99, 100).row)
    }

    @Test fun stableIdsCannotChangeOpaqueKindOrDowngradeArtifactSchema() {
        for ((name, discriminator) in listOf("tb_reading_artifacts" to "artifact_kind", "tb_book_wiki_entries" to "kind")) {
            val future = row(discriminator to "future.kind", "schema_version" to 9L,
                "payload_json" to "{\"future\":true}", "visible_from_progress" to 0.8)
            val changedKind = future + mapOf(discriminator to "known.kind", "updated_at" to 999L)
            val decision = ReadingAgentDelta.merge(table(name), changedKind, future, "local")
            assertNull(decision.row)
            assertTrue(decision.conflict)
            if (name == "tb_reading_artifacts") {
                val downgrade = future + mapOf("schema_version" to 1L, "updated_at" to 999L)
                assertTrue(ReadingAgentDelta.merge(table(name), downgrade, future, "local").conflict)
                assertNull(ReadingAgentDelta.merge(table(name), downgrade, future, "local").row)
            }
            val compatible = future + mapOf("updated_at" to 999L)
            assertEquals("{\"future\":true}", ReadingAgentDelta.merge(table(name), compatible, future, "local").row!!["payload_json"])
        }
    }

    @Test fun vocabularyIdentityMatchesExistingStoreNormalization() {
        for ((input, expected) in listOf("  Example! " to "example", "a   word" to "a word",
            "don't" to "don't", "中文" to "中文", "ÉCOLE" to "cole")) {
            assertEquals(expected, VocabularyDelta.normalizeWord(input))
            assertEquals("und:$expected", VocabularyDelta.identity(expected, "und"))
        }
        for (bad in listOf("Example", " example ", "example!", "a  word", ""))
            assertFailsWith<IllegalArgumentException> { VocabularyDelta.identity(bad, "und") }
    }

}
