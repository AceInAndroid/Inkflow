package com.aceinandroid.inkflow.transfer

import android.content.Context
import com.aceinandroid.inkflow.NativeSyncIntegration
import com.aceinandroid.inkflow.ai.NativeAiStore
import com.aceinandroid.inkflow.core.ai.SkillCodec
import com.aceinandroid.inkflow.core.ai.SkillImportPreview
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/** Durable, bounded staging only. Receiving or listing a Skill never installs it or invokes AI. */
internal class SkillTransferInbox(
    private val directory: File,
    private val parse: (ByteArray) -> SkillImportPreview,
    private val apply: (SkillImportPreview, Boolean) -> Unit,
    private val removeEntry: (File) -> Boolean = { it.deleteRecursively() },
    private val mutation: (() -> Confirmation) -> Confirmation = { it() },
) {
    constructor(context: Context) : this(
        File(context.applicationContext.filesDir, "skill-transfer-inbox"),
        { NativeAiStore(context.applicationContext).previewSkillImport(it) },
        { preview, overwrite -> NativeAiStore(context.applicationContext).applySkillImport(preview, overwrite) },
        mutation = { block -> NativeSyncIntegration.localMutation(block) },
    )

    data class Entry(val id: String, val name: String, val preview: SkillImportPreview, val version: String)
    enum class Confirmation { CONFIRMED, ALREADY_CONFIRMED }
    class PreviewChangedException : IllegalStateException("Skill preview changed; reopen and review it before confirming")
    class ConfirmationUncertainException : IllegalStateException(
        "Previous Skill confirmation was interrupted. Check installed Skills before discarding this upload; it will not be installed again")

    private data class Receipt(val version: String, val completed: Boolean)

    fun pending(): List<Entry> = synchronized(lock) {
        pendingFolders().sortedBy { it.name }.map { read(it.name) }
    }

    fun receive(name: String, bytes: ByteArray): Entry = synchronized(lock) {
        require(name.isNotBlank() && name.toByteArray(Charsets.UTF_8).size <= 240)
        require(bytes.size <= SkillCodec.MAX_FILE_BYTES) { "Skill file exceeds 64 KiB" }
        val preview = parse(bytes) // Parse before saving; no apply call on this path.
        check(directory.isDirectory || directory.mkdirs()) { "Cannot prepare Skill inbox" }
        require(pendingFolders().size < MAX_PENDING) { "Skill inbox is full" }
        val id = UUID.randomUUID().toString()
        val temporary = File(directory, ".$id")
        check(temporary.mkdir()) { "Cannot stage Skill" }
        try {
            File(temporary, "name").writeText(name, Charsets.UTF_8)
            File(temporary, "payload").writeBytes(bytes)
            check(temporary.renameTo(File(directory, id))) { "Cannot save Skill" }
        } finally { temporary.deleteRecursively() }
        Entry(id, name, preview, version(id, name, bytes, preview))
    }

    fun preview(id: String): Entry = synchronized(lock) { read(id) }

    /** Pass the exact entry shown in the approval dialog; a refreshed background list is not approval. */
    fun confirm(entry: Entry): Confirmation = confirm(entry.id, expectedVersion = entry.version)

    /**
     * Receipt precedes apply, and completion precedes cleanup. Registry and file writes cannot share a
     * transaction: an interrupted attempt has an explicitly uncertain outcome and is NEVER replayed.
     * This is at-most-once, not a claim of exactly-once delivery. Users may inspect and discard an
     * uncertain upload. A fresh upload requires a new preview and approval.
     *
     * The legacy ID-only call fails closed until the UI passes its displayed Entry/version.
     */
    fun confirm(id: String, overwrite: Boolean = false, expectedVersion: String? = null): Confirmation = synchronized(lock) {
        require(validId(id)) { "Invalid inbox ID" }
        require(!overwrite) { "WiFi Skill confirmation imports conflicts as copies" }
        require(expectedVersion != null && validVersion(expectedVersion)) { "Displayed Skill preview version is required" }
        receipt(id)?.let { return@synchronized priorConfirmation(id, expectedVersion, it) }
        mutation {
            val entry = read(id)
            if (entry.version != expectedVersion) throw PreviewChangedException()
            saveReceipt(id, Receipt(entry.version, completed = false))
            // Keep the durable attempt even when apply throws: a failure can occur after its commit.
            apply(entry.preview, false)
            saveReceipt(id, Receipt(entry.version, completed = true))
            cleanup(id)
            Confirmation.CONFIRMED
        }
    }

    fun reject(id: String) = synchronized(lock) {
        require(validId(id))
        // Do not erase the receipt: even a retained stale dialog must never replay an attempted import.
        check(removeEntry(File(directory, id))) { "Cannot remove Skill" }
    }

    private fun priorConfirmation(id: String, expectedVersion: String, receipt: Receipt): Confirmation {
        // An interrupted commit may itself change the conflict preview on restart.
        // Preserve the uncertainty diagnosis before comparing that refreshed preview.
        if (!receipt.completed) throw ConfirmationUncertainException()
        if (receipt.version != expectedVersion) throw PreviewChangedException()
        cleanup(id)
        return Confirmation.ALREADY_CONFIRMED
    }

    private fun cleanup(id: String) {
        // A durable completed receipt is authoritative even if cleanup fails or the process dies here.
        runCatching { removeEntry(File(directory, id)) }
    }

    private fun pendingFolders(): List<File> = directory.listFiles().orEmpty()
        .filter { validId(it.name) && it.isDirectory && receipt(it.name)?.completed != true }

    private fun read(id: String): Entry {
        require(validId(id)) { "Invalid inbox ID" }
        val folder = File(directory, id)
        val payload = File(folder, "payload")
        require(payload.isFile && payload.length() <= SkillCodec.MAX_FILE_BYTES) { "Invalid staged Skill" }
        val nameFile = File(folder, "name")
        require(nameFile.isFile && nameFile.length() <= 240) { "Invalid staged filename" }
        val name = nameFile.readText(Charsets.UTF_8)
        val bytes = payload.readBytes()
        val preview = parse(bytes)
        return Entry(id, name, preview, version(id, name, bytes, preview))
    }

    private fun receipt(id: String): Receipt? {
        val file = File(directory, ".receipts/$id")
        if (!file.exists()) return null
        check(file.isFile && file.length() <= 128) { "Invalid Skill confirmation receipt; cannot repeat import" }
        val fields = file.readText(Charsets.UTF_8).split('\n')
        check(fields.size == 3 && fields[0] == "1" && validVersion(fields[1]) && fields[2] in setOf("attempted", "completed")) {
            "Invalid Skill confirmation receipt; cannot repeat import"
        }
        return Receipt(fields[1], fields[2] == "completed")
    }

    private fun saveReceipt(id: String, receipt: Receipt) {
        val folder = File(directory, ".receipts")
        check(folder.isDirectory || folder.mkdirs()) { "Cannot prepare Skill confirmation receipt" }
        val temporary = File(folder, ".$id.${UUID.randomUUID()}")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write("1\n${receipt.version}\n${if (receipt.completed) "completed" else "attempted"}".toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            check(temporary.renameTo(File(folder, id))) { "Cannot save Skill confirmation receipt" }
        } finally { temporary.delete() }
    }

    private fun version(id: String, name: String, bytes: ByteArray, preview: SkillImportPreview): String {
        // Raw bytes protect the exact displayed payload; parsed content/conflicts protect the preview.
        val value = listOf(id, hash(name.toByteArray(Charsets.UTF_8)), hash(bytes),
            hash(SkillCodec.encodeJson(preview.skills).toByteArray(Charsets.UTF_8)),
            preview.conflictingIds.sorted().joinToString("\n"),
            hash(preview.conflictingSkills.sortedBy { it.id }.joinToString("\n") { SkillCodec.encodeJson(it) }
                .toByteArray(Charsets.UTF_8))).joinToString("\n")
        return hash(value.toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val MAX_PENDING = 20
        private val lock = Any()
        private fun validId(id: String) = id.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
        private fun validVersion(version: String) = version.matches(Regex("[a-f0-9]{64}"))
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
