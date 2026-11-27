package com.aceinandroid.inkflow.transfer

import com.aceinandroid.inkflow.core.ai.ReadingSkill
import com.aceinandroid.inkflow.core.ai.ReadingSkillRegistry
import com.aceinandroid.inkflow.core.ai.SkillCodec
import com.aceinandroid.inkflow.core.ai.SkillImportPreview
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SkillTransferInboxTest {
    private val skill = ReadingSkill("custom.review", "Review", "Summary", "Untrusted method text")
    private class Fixture(val directory: File, initial: List<ReadingSkill> = emptyList()) {
        var registry = ReadingSkillRegistry(initial)
        var applied = 0
        fun inbox(remove: (File) -> Boolean = { it.deleteRecursively() }, failAfterCommit: Boolean = false) =
            SkillTransferInbox(directory,
                { SkillImportPreview.create(SkillCodec.decode(it), registry) },
                { preview, overwrite ->
                    applied++
                    registry = ReadingSkillRegistry(preview.merge(registry, overwrite))
                    if (failAfterCommit) error("Process interrupted after Skill commit")
                }, remove)
    }
    private fun fixture(initial: List<ReadingSkill> = emptyList(), block: (Fixture) -> Unit) {
        val directory = Files.createTempDirectory("skill-inbox").toFile()
        try { block(Fixture(directory, initial)) } finally { directory.deleteRecursively() }
    }
    private inline fun <reified T : Throwable> rejects(block: () -> Unit): T {
        try { block() } catch (failure: Throwable) {
            if (failure is T) return failure
            throw failure
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }
    private fun SkillTransferInbox.receiveSkill() = receive("review.skill.md", SkillCodec.encodeMarkdown(skill).toByteArray())

    @Test fun receivingPreviewAndRestartNeverApplyUntilExplicitVersionedConfirmation() = fixture { f ->
        val received = f.inbox().receiveSkill()
        assertEquals(0, f.applied)
        assertNull(f.registry.find(skill.id))
        val restored = f.inbox()
        assertEquals(received, restored.pending().single())
        assertEquals(received, restored.preview(received.id))
        assertEquals(0, f.applied)
        assertEquals(SkillTransferInbox.Confirmation.CONFIRMED, restored.confirm(received))
        assertEquals(1, f.applied)
        assertEquals(skill, f.registry.find(skill.id))
        assertTrue(restored.pending().isEmpty())
        assertEquals(SkillTransferInbox.Confirmation.ALREADY_CONFIRMED, f.inbox().confirm(received))
        assertEquals(1, f.applied)
    }

    @Test fun registryConflictChangeRequiresNewPreviewBeforeDefaultCopyImport() = fixture { f ->
        val inbox = f.inbox()
        val old = inbox.receiveSkill()
        val local = skill.copy(instructions = "Keep local instructions")
        f.registry = ReadingSkillRegistry(listOf(local))
        rejects<SkillTransferInbox.PreviewChangedException> { inbox.confirm(old) }
        assertEquals(0, f.applied)
        assertFalse(File(f.directory, ".receipts/${old.id}").exists())
        val reviewed = inbox.preview(old.id)
        assertNotEquals(old.version, reviewed.version)
        assertEquals(setOf(skill.id), reviewed.preview.conflictingIds)
        inbox.confirm(reviewed)
        assertEquals(local, f.registry.find(skill.id))
        assertEquals(skill.instructions, f.registry.find("custom.review.copy1")?.instructions)
    }

    @Test fun editedExistingConflictRequiresFreshApprovalEvenWhenIdsStayTheSame() = fixture(listOf(skill.copy(instructions = "Original local"))) { f ->
        val inbox = f.inbox()
        val displayed = inbox.receiveSkill()
        val edited = skill.copy(instructions = "Edited while the approval dialog was open")
        f.registry = ReadingSkillRegistry(listOf(edited))
        rejects<SkillTransferInbox.PreviewChangedException> { inbox.confirm(displayed) }
        assertEquals(0, f.applied)
        assertFalse(File(f.directory, ".receipts/${displayed.id}").exists())
        assertEquals(edited, f.registry.find(skill.id))
        val reviewed = inbox.preview(displayed.id)
        assertEquals(displayed.preview.conflictingIds, reviewed.preview.conflictingIds)
        assertNotEquals(displayed.version, reviewed.version)
        inbox.confirm(reviewed)
        assertEquals(1, f.applied)
        assertEquals(edited, f.registry.find(skill.id))
        assertEquals(skill.instructions, f.registry.find("custom.review.copy1")?.instructions)
    }

    @Test fun changedPayloadOrFilenameCannotUsePreviouslyApprovedVersion() {
        listOf("payload", "name").forEach { changed -> fixture { f ->
            val inbox = f.inbox()
            val old = inbox.receiveSkill()
            File(f.directory, "${old.id}/$changed").writeText(if (changed == "payload")
                SkillCodec.encodeJson(skill.copy(instructions = "Changed after dialog opened")) else "renamed.skill.md")
            rejects<SkillTransferInbox.PreviewChangedException> { inbox.confirm(old) }
            assertEquals(0, f.applied)
            inbox.confirm(inbox.preview(old.id))
            assertEquals(1, f.applied)
        } }
    }

    @Test fun idOnlyAndAnotherEntryVersionCannotAuthorizeAnImport() = fixture { f ->
        val inbox = f.inbox()
        val first = inbox.receiveSkill()
        val second = inbox.receiveSkill()
        rejects<IllegalArgumentException> { inbox.confirm(first.id) }
        rejects<SkillTransferInbox.PreviewChangedException> { inbox.confirm(second.id, expectedVersion = first.version) }
        rejects<IllegalArgumentException> { inbox.confirm(first.id, overwrite = true, expectedVersion = first.version) }
        assertEquals(0, f.applied)
        inbox.confirm(first)
        inbox.confirm(inbox.preview(second.id)) // Distinct upload needs its own refreshed approval.
        assertEquals(2, f.applied)
        assertEquals(skill.instructions, f.registry.find("custom.review.copy1")?.instructions)
    }

    @Test fun completedReceiptPreventsDuplicateCopyAfterCleanupFailureAndRestart() = fixture(listOf(skill.copy(instructions = "Local"))) { f ->
        val inbox = f.inbox(remove = { false })
        val entry = inbox.receiveSkill()
        inbox.confirm(entry)
        assertTrue(File(f.directory, entry.id).isDirectory)
        assertTrue(inbox.pending().isEmpty())
        assertEquals(1, f.applied)
        assertEquals(SkillTransferInbox.Confirmation.ALREADY_CONFIRMED, f.inbox().confirm(entry))
        assertEquals(1, f.applied)
        assertEquals(2, f.registry.skills.count { it.id.startsWith("custom.") })
        assertNotNull(f.registry.find("custom.review.copy1"))
        assertNull(f.registry.find("custom.review.copy2"))
    }

    @Test fun commitThenExceptionLeavesDurableUncertainReceiptAndNeverReplaysOrOverwritesEdits() = fixture(listOf(skill.copy(instructions = "Local"))) { f ->
        val inbox = f.inbox(failAfterCommit = true)
        val entry = inbox.receiveSkill()
        rejects<IllegalStateException> { inbox.confirm(entry) }
        assertEquals(1, f.applied)
        assertNotNull(f.registry.find("custom.review.copy1"))
        val edited = f.registry.find("custom.review.copy1")!!.copy(instructions = "Edited after interrupted confirmation")
        f.registry = ReadingSkillRegistry(listOf(f.registry.find(skill.id)!!, edited))
        val restored = f.inbox()
        repeat(2) { rejects<SkillTransferInbox.ConfirmationUncertainException> { restored.confirm(entry) } }
        assertEquals(1, f.applied)
        assertEquals(edited, f.registry.find(edited.id))
        assertNull(f.registry.find("custom.review.copy2"))
        assertEquals(1, restored.pending().size)
        restored.reject(entry.id)
        rejects<SkillTransferInbox.ConfirmationUncertainException> { f.inbox().confirm(entry) }
        assertEquals(1, f.applied)
    }

    @Test fun interruptedFirstImportReportsUncertaintyAfterRestartRefreshesConflictPreview() = fixture { f ->
        val inbox = f.inbox(failAfterCommit = true)
        val original = inbox.receiveSkill()
        assertTrue(original.preview.conflictingIds.isEmpty())
        rejects<IllegalStateException> { inbox.confirm(original) }
        assertEquals(skill, f.registry.find(skill.id))

        val restarted = f.inbox()
        val refreshed = restarted.pending().single()
        assertNotEquals(original.version, refreshed.version)
        repeat(2) {
            rejects<SkillTransferInbox.ConfirmationUncertainException> { restarted.confirm(refreshed) }
        }
        assertEquals(1, f.applied)
        assertEquals(listOf(skill), f.registry.skills.filter { it.id.startsWith("custom.") })
        restarted.reject(refreshed.id)
        assertTrue(restarted.pending().isEmpty())
        rejects<SkillTransferInbox.ConfirmationUncertainException> { f.inbox().confirm(refreshed) }
        assertEquals(1, f.applied)
        assertEquals(skill, f.registry.find(skill.id))
    }

    @Test fun failedApplyRemainsPendingWithoutClaimingSuccessOrBlindlyReplaying() = fixture { f ->
        var attempts = 0
        val inbox = SkillTransferInbox(f.directory,
            { SkillImportPreview.create(SkillCodec.decode(it), f.registry) },
            { _, _ -> attempts++; error("Storage unavailable; commit outcome unknown") })
        val entry = inbox.receiveSkill()
        rejects<IllegalStateException> { inbox.confirm(entry) }
        assertEquals(entry, inbox.pending().single())
        rejects<SkillTransferInbox.ConfirmationUncertainException> { inbox.confirm(entry) }
        assertEquals(1, attempts)
        assertNull(f.registry.find(skill.id))
    }

    @Test fun receiptMustBeDurableBeforeAnyApplyAndPreAttemptFailureCanBeRetried() = fixture { f ->
        val inbox = f.inbox()
        val entry = inbox.receiveSkill()
        val obstruction = File(f.directory, ".receipts").apply { writeText("Not a directory") }
        rejects<IllegalStateException> { inbox.confirm(entry) }
        assertEquals(0, f.applied)
        assertEquals(entry, inbox.pending().single())
        assertTrue(obstruction.delete())
        inbox.confirm(entry)
        assertEquals(1, f.applied)
    }

    @Test fun corruptReceiptFailsClosedInsteadOfImportingAgain() = fixture { f ->
        val inbox = f.inbox(remove = { false })
        val entry = inbox.receiveSkill()
        inbox.confirm(entry)
        File(f.directory, ".receipts/${entry.id}").writeText("broken")
        rejects<IllegalStateException> { f.inbox().confirm(entry) }
        assertEquals(1, f.applied)
    }

    @Test fun syncGateFailureBeforeAttemptDoesNotBurnApproval() = fixture { f ->
        var busy = true
        val inbox = SkillTransferInbox(f.directory,
            { SkillImportPreview.create(SkillCodec.decode(it), f.registry) },
            { preview, overwrite -> f.applied++; f.registry = ReadingSkillRegistry(preview.merge(f.registry, overwrite)) },
            mutation = { block -> if (busy) error("Sync busy") else block() })
        val entry = inbox.receiveSkill()
        rejects<IllegalStateException> { inbox.confirm(entry) }
        assertFalse(File(f.directory, ".receipts/${entry.id}").exists())
        busy = false
        inbox.confirm(entry)
        assertEquals(1, f.applied)
    }

    @Test fun rejectsMalformedOversizedAndFullQueuesWithoutApplyingAndDiscardIsLocal() = fixture { f ->
        val inbox = f.inbox()
        rejects<IllegalArgumentException> { inbox.receive("bad.json", "{}".toByteArray()) }
        rejects<IllegalArgumentException> { inbox.receive("large.json", ByteArray(SkillCodec.MAX_FILE_BYTES + 1)) }
        assertTrue(inbox.pending().isEmpty())
        repeat(SkillTransferInbox.MAX_PENDING) { inbox.receiveSkill() }
        rejects<IllegalArgumentException> { inbox.receiveSkill() }
        rejects<IllegalArgumentException> { inbox.confirm("../payload") }
        inbox.reject(inbox.pending().first().id)
        assertEquals(SkillTransferInbox.MAX_PENDING - 1, inbox.pending().size)
        assertEquals(0, f.applied)
    }
}
