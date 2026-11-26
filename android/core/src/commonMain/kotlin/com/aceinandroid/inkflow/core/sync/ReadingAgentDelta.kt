package com.aceinandroid.inkflow.core.sync

/** Portable v1 Flutter ReadingAgentBookDelta rows; no provider, UI or database dependencies. */
typealias DeltaRow = Map<String, Any?>

data class DeltaTable(val name: String, val keys: List<String>, val entity: String? = null,
    val clock: String = "updated_at", val naturalKeys: List<String> = emptyList())

data class DeltaDecision(val row: DeltaRow?, val conflict: Boolean = false)

object ReadingAgentDelta {
    const val TYPE = "inkflow-reading-agent-book-delta"
    const val MAX_ROWS = 10_000
    const val MAX_BYTES = 2 * 1024 * 1024
    val tables = listOf(
        DeltaTable("tb_book_device_positions", listOf("book_id", "device_id")),
        DeltaTable("tb_book_reading_profiles", listOf("book_id")),
        DeltaTable("tb_book_reading_coverage", listOf("book_id")),
        DeltaTable("tb_reading_goals", listOf("id"), "goal"),
        DeltaTable("tb_reading_checkpoints", listOf("id"), "checkpoint",
            naturalKeys = listOf("book_id", "chapter_href")),
        DeltaTable("tb_reading_mastery", listOf("id"), "mastery"),
        DeltaTable("tb_knowledge_cards", listOf("id"), "knowledgeCard"),
        DeltaTable("tb_reading_memory_documents", listOf("id"), "memory"),
        DeltaTable("tb_reading_artifacts", listOf("id"), "artifact"),
        DeltaTable("tb_reading_difficulties", listOf("id"), "difficulty",
            naturalKeys = listOf("book_id", "cfi", "selected_text")),
        DeltaTable("tb_book_wikis", listOf("book_id")),
        DeltaTable("tb_book_wiki_entries", listOf("id"), "wikiEntry"),
        DeltaTable("tb_book_wiki_entry_sources", listOf("id"), "wikiSource", "created_at"),
        DeltaTable("tb_book_wiki_revisions", listOf("id"), "wikiRevision", "created_at"),
        DeltaTable("tb_reading_sync_tombstones", listOf("entity_type", "entity_id", "device_id"),
            clock = "deleted_at"),
    )

    /** Numeric local IDs are never cross-device book identities. */
    fun bookKey(md5: String?): String? = md5?.trim()?.lowercase()
        ?.takeIf { it.matches(Regex("[a-f0-9]{32}")) }

    fun remap(row: DeltaRow, bookId: Long): DeltaRow = row + ("book_id" to bookId)
    fun timestamp(row: DeltaRow, column: String = "updated_at"): Long =
        (row[column] as? Number)?.toLong() ?: 0L

    fun deleted(row: DeltaRow, deletedAt: Long, clock: String = "updated_at"): Boolean =
        deletedAt > 0 && deletedAt >= timestamp(row, clock)

    fun merge(table: DeltaTable, incoming: DeltaRow, current: DeltaRow?, localDevice: String): DeltaDecision {
        if (table.name == "tb_book_device_positions" && incoming["device_id"] == localDevice)
            return DeltaDecision(null)
        if (current != null && incoming["book_id"] != current["book_id"] &&
            (incoming["book_id"] as? Number)?.toLong() != (current["book_id"] as? Number)?.toLong())
            return DeltaDecision(null, true)
        if (current != null) {
            val discriminator = when (table.name) {
                "tb_reading_artifacts" -> "artifact_kind"
                "tb_book_wiki_entries" -> "kind"
                else -> null
            }
            if (discriminator != null && incoming[discriminator] != current[discriminator])
                return DeltaDecision(null, true)
            if (table.name == "tb_reading_artifacts" && timestamp(current, "schema_version") > timestamp(incoming, "schema_version"))
                return DeltaDecision(null, true)
        }
        val next = incoming.toMutableMap()
        if (current != null && table.naturalKeys.isNotEmpty()) next["id"] = current["id"]
        // Unknown kinds and JSON payloads remain opaque. Arrival order never lowers visibility.
        if (table.name in setOf("tb_reading_artifacts", "tb_book_wiki_entries")) {
            next["visible_from_progress"] = maxOf(number(next["visible_from_progress"]),
                number(current?.get("visible_from_progress")), number(next["source_progress"]),
                number(current?.get("source_progress")))
        }
        if (table.name in setOf("tb_book_reading_coverage", "tb_book_wikis")) {
            // A remote installation cannot expand this device's default generation boundary.
            next["safe_knowledge_boundary"] = current?.get("safe_knowledge_boundary") ?: 0.0
            if (current != null) {
                val prefix = if (table.name == "tb_book_wikis") "coverage" else "artifact_coverage"
                next["${prefix}_start"] = minOf(number(current["${prefix}_start"]), number(next["${prefix}_start"]))
                next["${prefix}_end"] = maxOf(number(current["${prefix}_end"]), number(next["${prefix}_end"]))
                if (table.name == "tb_book_reading_coverage" && rank(next["setup_status"]) < rank(current["setup_status"]))
                    next["setup_status"] = current["setup_status"]
                if (table.name == "tb_book_reading_coverage")
                    next["initialized_at_progress"] = current["initialized_at_progress"]
            }
        }
        if (current == null) return DeltaDecision(next)
        val protection = when (table.name) {
            "tb_book_reading_profiles" -> flag(next["pinned"]).compareTo(flag(current["pinned"]))
            "tb_book_wiki_entries" -> flag(next["user_corrected"]).compareTo(flag(current["user_corrected"]))
            else -> 0
        }
        val version = if (table.name == "tb_book_wiki_entries")
            timestamp(next, "version").compareTo(timestamp(current, "version")) else 0
        val clock = timestamp(next, table.clock).compareTo(timestamp(current, table.clock))
        val state = rank(next[if (table.name == "tb_reading_mastery") "level" else "status"])
            .compareTo(rank(current[if (table.name == "tb_reading_mastery") "level" else "status"]))
        val winner = when {
            protection != 0 -> protection
            version != 0 -> version
            clock != 0 -> clock
            else -> state
        }
        if (winner > 0) return DeltaDecision(next)
        // Preserve a stricter remote spoiler boundary even when its payload loses LWW.
        val guarded = current.toMutableMap()
        if ("visible_from_progress" in next) guarded["visible_from_progress"] = next["visible_from_progress"]
        if (table.name in setOf("tb_book_reading_coverage", "tb_book_wikis")) {
            val prefix = if (table.name == "tb_book_wikis") "coverage" else "artifact_coverage"
            guarded["${prefix}_start"] = next["${prefix}_start"]
            guarded["${prefix}_end"] = next["${prefix}_end"]
            if (table.name == "tb_book_reading_coverage") guarded["setup_status"] = next["setup_status"]
        }
        val changed = !same(guarded, current)
        return DeltaDecision(guarded.takeIf { changed }, winner == 0 && !same(next, current))
    }

    fun same(a: DeltaRow, b: DeltaRow): Boolean = a.keys == b.keys && a.all { (key, value) ->
        val other = b[key]
        if (value is Number && other is Number) value.toDouble() == other.toDouble() else value == other
    }

    private fun number(value: Any?): Double = (value as? Number)?.toDouble() ?: 0.0
    private fun flag(value: Any?): Int = if ((value as? Number)?.toInt() == 1) 1 else 0
    private fun rank(value: Any?): Int = when (value) {
        "suspended", "fromHere", "emerging" -> 1
        "resolved", "skipped", "abandoned", "imported", "familiar" -> 2
        "completed", "mastered", "backfilled" -> 3
        "retracted" -> 4
        else -> 0
    }
}

/** v1 has no language column locally: only the explicit `und` namespace is representable. */
object VocabularyDelta {
    const val TYPE = "inkflow-vocabulary-delta"
    const val LANGUAGE = "und"
    const val ENTITY = "vocabulary"
    fun normalizeWord(word: String): String {
        val lower = word.trim().lowercase()
        return lower.replace(Regex("^[^a-z0-9]+|[^a-z0-9]+$"), "")
            .replace(Regex("\\s+"), " ").ifEmpty { lower }
    }

    fun identity(normalizedWord: String, language: String): String {
        require(language == LANGUAGE) { "Vocabulary language cannot be represented by v22" }
        require(normalizedWord.isNotBlank() && normalizedWord.length <= 256 && normalizeWord(normalizedWord) == normalizedWord)
        return "$language:$normalizedWord"
    }

    fun merge(incoming: DeltaRow, current: DeltaRow?, incomingTime: Long, currentTime: Long): DeltaDecision {
        if (current == null) return DeltaDecision(incoming)
        val next = incoming + ("id" to current["id"])
        return when {
            incomingTime > currentTime -> DeltaDecision(next)
            incomingTime < currentTime -> DeltaDecision(null)
            else -> DeltaDecision(null, !ReadingAgentDelta.same(next, current))
        }
    }
}
