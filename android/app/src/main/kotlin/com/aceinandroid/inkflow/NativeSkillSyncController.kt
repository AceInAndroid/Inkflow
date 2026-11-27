package com.aceinandroid.inkflow

import android.content.Context
import com.aceinandroid.inkflow.ai.NativeAiStore
import com.aceinandroid.inkflow.core.ai.ReadingSkill
import com.aceinandroid.inkflow.core.ai.ReadingSkillRegistry
import com.aceinandroid.inkflow.core.ai.SkillCodec
import com.aceinandroid.inkflow.core.ai.SkillImportPreview
import com.aceinandroid.inkflow.core.sync.DeltaRow
import java.security.MessageDigest

/** Skill definitions travel as existing artifact records, never as device preferences or bindings. */
internal class NativeSkillSyncController(
    private val stored: () -> String,
    private val apply: (SkillImportPreview) -> Unit,
    private val revision: (String, String) -> Long,
    private val received: (String) -> Boolean,
    private val acknowledge: (String) -> Unit,
    private val sourceIdentity: (String) -> String? = { null },
    private val rememberSourceIdentity: (String, String) -> Unit = { _, _ -> },
) {
    constructor(context: Context) : this(
        { NativeAiStore(context.applicationContext).exportStoredSkills() },
        { NativeAiStore(context.applicationContext).applySkillImport(it) },
        { id, digest ->
            val prefs = context.applicationContext.getSharedPreferences("native_skill_sync", Context.MODE_PRIVATE)
            synchronized(lock) {
                val key = hash(id)
                if (prefs.getString("hash.$key", null) == digest) prefs.getLong("time.$key", 1L)
                else maxOf(System.currentTimeMillis(), prefs.getLong("time.$key", 0L) + 1).also { time ->
                    check(prefs.edit().putString("hash.$key", digest).putLong("time.$key", time).commit())
                }
            }
        },
        { digest -> context.applicationContext.getSharedPreferences("native_skill_sync", Context.MODE_PRIVATE)
            .getBoolean("received.$digest", false) },
        { digest -> check(context.applicationContext.getSharedPreferences("native_skill_sync", Context.MODE_PRIVATE)
            .edit().putBoolean("received.$digest", true).commit()) },
        { digest -> context.applicationContext.getSharedPreferences("native_skill_sync", Context.MODE_PRIVATE)
            .getString("source.$digest", null) },
        { digest, id -> check(context.applicationContext.getSharedPreferences("native_skill_sync", Context.MODE_PRIVATE)
            .edit().putString("source.$digest", id).commit()) },
    )

    // Keyed by the complete local definition: editing/reusing a copy ID cannot alias unrelated data.
    private val sources = mutableMapOf<String, String>()

    private fun sourceId(skill: ReadingSkill): String? {
        val digest = hash(SkillCodec.encodeJson(skill))
        return sources[digest] ?: sourceIdentity(digest)
    }

    private fun rememberSource(skill: ReadingSkill, id: String) {
        if (skill.id == id || sourceId(skill) == id) return
        val digest = hash(SkillCodec.encodeJson(skill))
        rememberSourceIdentity(digest, id)
        sources[digest] = id
    }

    fun capture(bookKey: String, bookId: Long, reservedIds: Set<String> = emptySet()): List<DeltaRow> = synchronized(lock) {
        SkillCodec.decodeStored(stored()).filter { it.id.startsWith("custom.") }.map { skill ->
            // Local copy IDs belong to the registry. Re-export their original source identity so
            // another device recognizes its own definition rather than copying the copy again.
            val payload = SkillCodec.encodeJson(skill.copy(id = sourceId(skill) ?: skill.id))
            val digest = hash(payload)
            val time = revision(skill.id, digest)
            // Remember locally published versions too, so a stale branch cannot resurrect a
            // definition the user subsequently removed or edited on this device.
            if (!received(digest)) acknowledge(digest)
            // An opaque future record owns its ID. Keep the current definition in a stable,
            // version-separated record instead; content edits must not change this identity.
            val canonicalId = "skill.${hash("$bookKey:${skill.id}")}"
            var recordId = canonicalId
            var collision = 0
            while (recordId in reservedIds) {
                recordId = "skill.compat.${hash("$canonicalId:${SkillCodec.SCHEMA_VERSION}:${collision++}")}"
            }
            mapOf("id" to recordId, "book_id" to bookId,
                "module_id" to MODULE, "artifact_kind" to KIND, "schema_version" to SkillCodec.SCHEMA_VERSION,
                "payload_json" to payload, "epistemic_status" to "userReflection", "status" to "active",
                "source_text_snapshot" to "", "source_progress" to 0.0, "visible_from_progress" to 0.0,
                "ingested_at" to time, "ingestion_mode" to "synced",
                "created_by" to "user", "created_at" to time, "updated_at" to time)
        }
    }

    /** Unknown kinds/versions stay opaque in the normal sync store and cannot become executable Skills. */
    fun definition(row: DeltaRow): ReadingSkill? {
        if (row["module_id"] != MODULE || row["artifact_kind"] != KIND ||
            (row["schema_version"] as? Number)?.toDouble() != SkillCodec.SCHEMA_VERSION.toDouble()) return null
        require(row["status"] == "active") { "Invalid Skill sync status" }
        val skills = SkillCodec.decode(row["payload_json"] as? String ?: error("Missing Skill definition"))
        require(skills.size == 1 && skills.single().id.startsWith("custom.")) { "Expected one custom Skill" }
        return skills.single()
    }

    /** Idempotent across book branches and transport retries; existing IDs use the common copy policy. */
    fun merge(definitions: List<ReadingSkill>) = synchronized(lock) {
        for (skill in definitions) {
            val digest = hash(SkillCodec.encodeJson(skill))
            if (received(digest)) continue
            val registry = ReadingSkillRegistry(SkillCodec.decodeStored(stored()))
            val normalized = skill.copy(schemaVersion = SkillCodec.SCHEMA_VERSION)
            val existing = registry.skills.firstOrNull { candidate ->
                (sourceId(candidate) ?: candidate.id) == skill.id &&
                    candidate.copy(id = skill.id, schemaVersion = SkillCodec.SCHEMA_VERSION) == normalized
            }
            if (existing != null) {
                rememberSource(existing, skill.id)
            } else {
                val preview = SkillImportPreview.create(listOf(skill), registry)
                // Persist the planned allocation before applying, including long IDs whose first
                // copy is only a truncated base. A crash after import must not lose its source.
                rememberSource(preview.merge(registry).last(), skill.id)
                apply(preview)
                // Read the actual allocated copy ID: the shared import boundary revalidates against
                // current storage and may allocate a different suffix after a concurrent import.
                val previousIds = registry.skills.map { it.id }.toSet()
                val imported = SkillCodec.decodeStored(stored()).single { candidate ->
                    candidate.id !in previousIds &&
                        candidate.copy(id = skill.id, schemaVersion = SkillCodec.SCHEMA_VERSION) == normalized
                }
                rememberSource(imported, skill.id)
            }
            acknowledge(digest)
        }
    }

    companion object {
        // Reserved global branch: existing book-delta envelope, no synthetic library book required.
        const val BOOK_KEY = "00000000000000000000000000000000"
        const val DIRECTORY = "inkflow/data/reading-skills/v1"
        const val MODULE = "reading.skills"
        const val KIND = "reading.skill.definition"
        private val lock = Any()
        private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
