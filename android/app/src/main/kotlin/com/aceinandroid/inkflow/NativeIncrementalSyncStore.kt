package com.aceinandroid.inkflow

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.aceinandroid.inkflow.core.ai.ReadingSkill
import com.aceinandroid.inkflow.core.sync.DeltaRow
import com.aceinandroid.inkflow.core.sync.DeltaTable
import com.aceinandroid.inkflow.core.sync.ReadingAgentDelta
import com.aceinandroid.inkflow.core.sync.VocabularyDelta
import java.io.IOException

/** The sync persistence boundary: validated rows only, one atomic transaction per branch. */
internal class NativeIncrementalSyncStore(
    private val db: SQLiteDatabase,
    private val device: String,
    private val skillSync: NativeSkillSyncController? = null,
) {
    private val columns = mutableMapOf<String, Set<String>>()
    private val tombstone = ReadingAgentDelta.tables.last()
    private val vocabulary = DeltaTable("tb_vocabulary", listOf("normalized_word"), VocabularyDelta.ENTITY)

    fun books(): Map<String, Long> = db.rawQuery(
        "SELECT id, file_md5 FROM tb_books WHERE is_deleted = 0 ORDER BY id LIMIT 129", null).use { rows ->
        val result = linkedMapOf<String, Long>()
        var count = 0
        while (rows.moveToNext()) {
            require(++count <= 128) { "Incremental sync book limit exceeded" }
            ReadingAgentDelta.bookKey(rows.getString(1))?.let { key ->
                require(key !in result) { "Ambiguous local book identity" }
                result[key] = rows.getLong(0)
            }
        }
        result
    }

    fun capture(bookKey: String?, bookId: Long? = null): NativeIncrementalSyncPackage = transaction {
        var count = 0
        val keysById = if (bookKey == null) books().entries.associate { it.value.toString() to it.key } else emptyMap()
        val skills = bookKey == NativeSkillSyncController.BOOK_KEY && bookId == 0L
        val tables = if (skills) listOf(ReadingAgentDelta.tables.first { it.name == "tb_reading_artifacts" })
            else if (bookKey == null) listOf(vocabulary, tombstone) else ReadingAgentDelta.tables
        val records = tables.associate { table ->
            val where = if (bookKey == null) {
                if (table == tombstone) "book_id = 0 AND entity_type = 'vocabulary'" else null
            } else if (skills) "book_id = ? AND module_id = 'reading.skills'" else "book_id = ?"
            val args = bookId?.let { arrayOf(it.toString()) }
            val storedRows = query(table.name, where, args, ReadingAgentDelta.MAX_ROWS - count + 1)
            val rows = if (bookKey != null && table.name == "tb_reading_artifacts" && skillSync != null) {
                // Preserve opaque records verbatim, without allowing their IDs to suppress a
                // current local definition. The bridge allocates a stable compatibility ID.
                val opaque = storedRows.filter { skillSync.definition(it) == null }
                val reserved = opaque.map { requireNotNull(it["id"] as? String) }.toSet()
                opaque + skillSync.capture(bookKey, requireNotNull(bookId), reserved)
            } else storedRows
            count += rows.size
            require(count <= ReadingAgentDelta.MAX_ROWS) { "Incremental sync row limit exceeded" }
            table.name to if (table == vocabulary) rows.map { row ->
                val key = keysById[row["book_id"].toString()]
                // Never expose or rely on an installation's numeric book ID in vocabulary packages.
                row + mapOf("book_id" to "", "book_key" to key)
            } else rows
        }
        NativeIncrementalSyncPackage(if (bookKey == null) VocabularyDelta.TYPE else ReadingAgentDelta.TYPE,
            device, System.currentTimeMillis(), records, bookKey).also { it.encode() }
    }

    /** Equal-time payload conflicts keep the local value and are reported without interrupting reading. */
    fun merge(packageData: NativeIncrementalSyncPackage, bookId: Long? = null): Int {
        val definitions = mutableListOf<ReadingSkill>()
        val conflicts = mergeRows(packageData, bookId, definitions)
        // SQLite first, then the existing Skill persistence boundary. A failed import leaves the
        // branch unacknowledged so the normal protocol can retry; no page or sync SQL writes Skills.
        skillSync?.merge(definitions)
        return conflicts
    }

    private fun mergeRows(packageData: NativeIncrementalSyncPackage, bookId: Long?,
        definitions: MutableList<ReadingSkill>): Int = transaction {
        require((packageData.type == ReadingAgentDelta.TYPE) == (bookId != null))
        require(packageData.rows.values.sumOf { it.size } <= ReadingAgentDelta.MAX_ROWS)
        val vocab = bookId == null
        val localBooks = if (vocab) books() else emptyMap()
        val skills = packageData.bookKey == NativeSkillSyncController.BOOK_KEY && bookId == 0L
        val specs = if (skills) listOf(ReadingAgentDelta.tables.first { it.name == "tb_reading_artifacts" })
            else if (vocab) listOf(vocabulary, tombstone) else ReadingAgentDelta.tables
        // Validate the entire package before the first write; unknown columns must not be discarded.
        packageData.rows.forEach { (name, rows) ->
            val spec = specs.firstOrNull { it.name == name } ?: error("Unsupported table")
            rows.forEach { raw ->
                validate(spec, raw, vocab)
                if (skills) require(raw["module_id"] == NativeSkillSyncController.MODULE)
                if (spec.name == "tb_reading_artifacts") skillSync?.definition(raw)
            }
        }
        var conflicts = 0
        val incomingTombstones = packageData.rows[tombstone.name].orEmpty()
        for (raw in incomingTombstones) {
            val row = ReadingAgentDelta.remap(raw, bookId ?: 0)
            val current = find(tombstone, row)
            val decision = ReadingAgentDelta.merge(tombstone, row, current, device)
            if (decision.conflict) conflicts++
            decision.row?.let { write(tombstone, it, current) }
        }
        // Apply all stored tombstones as well as this branch's, so old rows cannot resurrect.
        val dead = query(tombstone.name, "book_id = ?", arrayOf((bookId ?: 0).toString()))
        for (stone in dead) {
            val target = specs.firstOrNull { it.entity == stone["entity_type"] } ?: continue
            val key = if (target == vocabulary) (stone["entity_id"] as String).removePrefix("und:") else stone["entity_id"]
            val current = query(target.name, "${target.keys.first()} = ? AND book_id = ?".takeUnless { vocab }
                ?: "normalized_word = ?", if (vocab) arrayOf(key.toString()) else arrayOf(key.toString(), bookId.toString()), 1).firstOrNull()
            if (current != null && deleted(target, current, stone))
                db.delete(target.name, "${target.keys.first()} = ?", arrayOf(current[target.keys.first()].toString()))
        }
        for (spec in specs.filter { it != tombstone }) {
            for (raw in packageData.rows[spec.name].orEmpty()) {
                val incoming = if (vocab) vocabularyBook(raw, localBooks) else ReadingAgentDelta.remap(raw, bookId!!)
                if (dead.any { stone -> stone["entity_type"] == spec.entity &&
                        stone["entity_id"] == (if (vocab) VocabularyDelta.identity(incoming["normalized_word"] as String, "und") else incoming["id"])
                        && deleted(spec, incoming, stone) }) continue
                val current = find(spec, incoming)
                // A globally stable ID from another book must never overwrite an unrelated row.
                if (current != null && !vocab && (current["book_id"] as Number).toLong() != bookId) {
                    conflicts++; continue
                }
                val decision = if (vocab) VocabularyDelta.merge(incoming, current,
                    NativeIncrementalSyncTimestamp.micros(incoming["updated_at"]),
                    current?.let { NativeIncrementalSyncTimestamp.micros(it["updated_at"]) } ?: 0)
                else ReadingAgentDelta.merge(spec, incoming, current, device)
                if (decision.conflict) conflicts++
                val winner = decision.row ?: continue
                if (spec.name == "tb_reading_goals" && winner["status"] == "active" && !resolveGoal(winner)) {
                    conflicts++; continue
                }
                val definition = if (spec.name == "tb_reading_artifacts") skillSync?.definition(winner) else null
                if (definition != null) definitions += definition else write(spec, winner, current)
            }
        }
        conflicts
    }

    /** Call in the same local mutation gate as vocabulary removal; v22 has no vocabulary delete marker. */
    fun deleteVocabulary(normalizedWord: String, now: Long = System.currentTimeMillis()) = transaction {
        val identity = VocabularyDelta.identity(normalizedWord, VocabularyDelta.LANGUAGE)
        val existing = query(vocabulary.name, "normalized_word = ?", arrayOf(normalizedWord), 1).firstOrNull()
            ?: return@transaction
        require(NativeIncrementalSyncTimestamp.validMillis(now))
        val markerKey = mapOf("entity_type" to VocabularyDelta.ENTITY, "entity_id" to identity, "device_id" to device)
        val previous = find(tombstone, markerKey)
        val rowCeiling = (NativeIncrementalSyncTimestamp.micros(existing["updated_at"]) + 999) / 1_000
        val deletedAt = maxOf(now, rowCeiling, previous?.let { ReadingAgentDelta.timestamp(it, "deleted_at") } ?: 0)
        require(NativeIncrementalSyncTimestamp.validMillis(deletedAt))
        val marker = markerKey + mapOf("book_id" to 0L, "deleted_at" to deletedAt)
        write(tombstone, marker, previous)
        db.delete(vocabulary.name, "normalized_word = ?", arrayOf(normalizedWord))
    }

    private fun validate(spec: DeltaTable, row: DeltaRow, vocab: Boolean) {
        val allowed = columns.getOrPut(spec.name) {
            db.rawQuery("PRAGMA table_info(${spec.name})", null).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
            }
        } + if (spec == vocabulary) setOf("book_key") else emptySet()
        require(allowed.isNotEmpty() && row.keys.all { it in allowed }) { "Unsupported sync columns" }
        spec.keys.forEach { key ->
            if (key == "book_id") require(row[key] is Number)
            else require(row[key] is String && (row[key] as String).isNotBlank() && (row[key] as String).length <= 512)
        }
        for ((key, value) in row) {
            if (key.endsWith("progress") || key in setOf("safe_knowledge_boundary", "artifact_coverage_start", "artifact_coverage_end", "coverage_start", "coverage_end"))
                require(value is Number && value.toDouble().isFinite() && value.toDouble() in 0.0..1.0)
        }
        if (spec.name in setOf("tb_reading_artifacts", "tb_book_wiki_entries"))
            require(row["visible_from_progress"] is Number) { "Missing spoiler boundary" }
        if (spec.name == "tb_reading_artifacts") require(row["source_progress"] is Number)
        if (spec == vocabulary) {
            VocabularyDelta.identity(row["normalized_word"] as String, "und")
            require(vocabularyTime(row["updated_at"]) > 0)
            require(row["word"] is String && (row["word"] as String).isNotBlank())
            require(row["book_key"] == null || row["book_key"] is String && ReadingAgentDelta.bookKey(row["book_key"] as String) == row["book_key"])
        } else {
            val clock = row[spec.clock]
            require(NativeIncrementalSyncTimestamp.validMillis(clock))
        }
        if (spec == tombstone && vocab) {
            require(row["entity_type"] == VocabularyDelta.ENTITY)
            require(row["entity_id"] is String)
            val identity = row["entity_id"] as String
            require(identity.startsWith("und:"))
            VocabularyDelta.identity(identity.removePrefix("und:"), "und")
        }
        if (spec.name == "tb_book_device_positions") require(NativeIncrementalSyncPackage.validDevice(row["device_id"] as String))
    }

    private fun vocabularyBook(raw: DeltaRow, localBooks: Map<String, Long>): DeltaRow {
        val key = raw["book_key"] as? String
        val id = key?.let { localBooks[it] }
        return (raw - "book_key") + ("book_id" to (id?.toString() ?: "")) +
            if (id == null) mapOf("position" to null, "chapter_id" to null) else emptyMap()
    }

    private fun deleted(spec: DeltaTable, row: DeltaRow, stone: DeltaRow): Boolean {
        val deletedAt = ReadingAgentDelta.timestamp(stone, "deleted_at")
        if (spec == vocabulary) {
            require(NativeIncrementalSyncTimestamp.validMillis(deletedAt))
            return deletedAt * 1_000 >= NativeIncrementalSyncTimestamp.micros(row["updated_at"])
        }
        return deletedAt >= ReadingAgentDelta.timestamp(row, spec.clock)
    }

    private fun resolveGoal(row: DeltaRow): Boolean {
        val active = query("tb_reading_goals", "book_id = ? AND status = 'active' AND id != ?",
            arrayOf(row["book_id"].toString(), row["id"].toString()))
        val latest = active.maxWithOrNull(compareBy<DeltaRow> { ReadingAgentDelta.timestamp(it) }.thenBy { it["id"].toString() })
        if (latest != null && (ReadingAgentDelta.timestamp(latest) > ReadingAgentDelta.timestamp(row) ||
                ReadingAgentDelta.timestamp(latest) == ReadingAgentDelta.timestamp(row) && latest["id"].toString() > row["id"].toString())) {
            // Keep both records; the unique active-goal invariant still holds.
            write(ReadingAgentDelta.tables.first { it.entity == "goal" }, row + ("status" to "abandoned"), find(ReadingAgentDelta.tables.first { it.entity == "goal" }, row))
            return false
        }
        db.update("tb_reading_goals", ContentValues().apply { put("status", "abandoned") },
            "book_id = ? AND status = 'active' AND id != ?", arrayOf(row["book_id"].toString(), row["id"].toString()))
        return true
    }

    private fun find(spec: DeltaTable, row: DeltaRow): DeltaRow? {
        val byId = query(spec.name, spec.keys.joinToString(" AND ") { "$it = ?" },
            spec.keys.map { row[it].toString() }.toTypedArray(), 1).firstOrNull()
        return byId ?: if (spec.naturalKeys.isEmpty()) null else query(spec.name,
            spec.naturalKeys.joinToString(" AND ") { "$it = ?" }, spec.naturalKeys.map { row[it].toString() }.toTypedArray(), 1).firstOrNull()
    }

    private fun write(spec: DeltaTable, row: DeltaRow, current: DeltaRow?) {
        val values = ContentValues().apply { row.forEach { (key, value) -> when (value) {
            null -> putNull(key)
            is String -> put(key, value)
            is Int -> put(key, value)
            is Long -> put(key, value)
            is Number -> put(key, value.toDouble())
            else -> error("Unsupported sync value")
        } } }
        if (current == null) db.insertOrThrow(spec.name, null, values)
        else check(db.update(spec.name, values, spec.keys.joinToString(" AND ") { "$it = ?" },
            spec.keys.map { current[it].toString() }.toTypedArray()) == 1)
    }

    private fun query(table: String, where: String?, args: Array<String>?, limit: Int = ReadingAgentDelta.MAX_ROWS + 1): List<DeltaRow> =
        db.query(table, null, where, args, null, null, null, limit.toString()).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.columnNames.indices.associate { index ->
                cursor.getColumnName(index) to when (cursor.getType(index)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                    Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                    Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
                    else -> throw IOException("Unsupported binary sync value")
                }
            }) }.also { require(it.size <= ReadingAgentDelta.MAX_ROWS) { "Incremental sync row limit exceeded" } }
        }

    private fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        try { return block().also { db.setTransactionSuccessful() } }
        finally { db.endTransaction() }
    }

    companion object {
        fun vocabularyTime(value: Any?): Long = NativeIncrementalSyncTimestamp.millis(value)
    }
}
