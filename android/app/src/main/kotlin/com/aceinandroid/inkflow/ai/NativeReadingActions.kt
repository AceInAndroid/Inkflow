package com.aceinandroid.inkflow.ai

import android.content.ContentValues
import com.aceinandroid.inkflow.BookStore
import com.aceinandroid.inkflow.ReadingNoteWorkspaceStore
import com.aceinandroid.inkflow.core.ReadingContextSnapshot
import com.aceinandroid.inkflow.core.ai.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class NativeReadingUndoResult { UNDONE, ALREADY_UNDONE, CONFLICT, EXPIRED, MISSING, UNSUPPORTED }
data class GeneratedBookMaterial(val kind: String, val title: String, val summary: String,
                                 val body: String, val evidence: ReadingEvidence, val relationship: BookRelationship? = null, val structure: ReadingStructureUnit? = null)
data class ReadingActionRecord(val id: String, val targetId: String, val status: String, val createdAt: Long, val title: String = "")

/** The native P1 validated mutation boundary. Every write and its undo journal share a transaction. */
class NativeReadingActions(private val store: BookStore, private val clock: () -> Long = System::currentTimeMillis) {
    private val db get() = store.writableDatabase
    private data class Scope(val table: String, val key: String, val value: String, val restore: Boolean = true)
    private val allowed = mapOf(
        "tb_book_wiki_entries" to "id", "tb_book_wiki_entry_sources" to "entry_id",
        "tb_book_wiki_revisions" to "entry_id", "tb_book_wikis" to "book_id",
        "tb_reading_artifacts" to "id", "tb_reading_note_ai_batches" to "id",
        "tb_reading_note_ai_suggestions" to "id", "tb_reading_notes" to "id",
        "tb_reading_note_blocks" to "note_id", "tb_reading_note_sources" to "note_id",
        "tb_reading_note_tags" to "note_id", "tb_reading_note_revisions" to "note_id", "tb_notes" to "id", "tb_reading_tags" to "id", "tb_reading_goals" to "id",
        "tb_reading_checkpoints" to "id", "tb_reading_difficulties" to "id",
    )
    private fun noteScopes(id: String) = listOf("tb_reading_notes", "tb_reading_note_blocks", "tb_reading_note_sources",
        "tb_reading_note_tags", "tb_reading_note_revisions").map { Scope(it, allowed.getValue(it), id) }
    private fun wikiScopes(id: String) = listOf("tb_book_wiki_entries", "tb_book_wiki_entry_sources",
        "tb_book_wiki_revisions").map { Scope(it, allowed.getValue(it), id) }
    private fun activeBook(bookId: Long): JSONObject = ReadingSql.rows(db, "tb_books",
        "id = ? AND is_deleted = 0", arrayOf(bookId.toString())).singleOrNull() ?: error("Book no longer exists")

    fun saveAiNote(bookId: Long, title: String, content: String, quote: String?,
                   snapshot: ReadingContextSnapshot, userAuthorized: Boolean): String {
        require(userAuthorized) { "Explicit save is required" }
        require(title.isNotBlank() && content.isNotBlank() && title.length <= 500 && content.length <= 100_000)
        require(snapshot.progressPercent in 0..100 && snapshot.readingProgress.isFinite() && snapshot.readingProgress in 0.0..1.0)
        val sourceQuote = quote?.trim().orEmpty()
        require(sourceQuote.isEmpty() || snapshot.selectedText?.contains(sourceQuote) == true ||
            snapshot.precedingContext.contains(sourceQuote)) { "Quote is not in the captured reading context" }
        return ReadingSql.transaction(db) {
            require(activeBook(bookId).optString("title") == snapshot.bookTitle) { "Reading context belongs to another book" }
            val id = ReadingNoteWorkspaceStore(store).createManual(bookId, title, content, emptyList(), "active", false)
            val scopes = noteScopes(id)
            val before = snapshot(scopes, empty = true)
            val now = clock()
            db.update("tb_reading_notes", ContentValues().apply { put("capture_kind", "ai") }, "id = ?", arrayOf(id))
            db.update("tb_reading_note_blocks", ContentValues().apply {
                put("origin", "ai")
                put("metadata", JSONObject().put("nativeReading", true).put("chapter", snapshot.chapterTitle)
                    .put("visibleFromProgress", snapshot.readingProgress).put("chapterIndex", snapshot.chapterIndex).put("readOffset", snapshot.readOffset).toString())
            }, "note_id = ?", arrayOf(id))
            db.insertOrThrow("tb_reading_note_sources", null, ContentValues().apply {
                put("note_id", id); put("source_type", "aiConversation"); put("source_ref", "native:$id")
                put("chapter_title", snapshot.chapterTitle); put("text_snapshot", sourceQuote)
                put("metadata", JSONObject().put("contextSnapshot", JSONObject()
                    .put("bookTitle", snapshot.bookTitle).put("chapterTitle", snapshot.chapterTitle)
                    .put("progressPercent", snapshot.progressPercent).put("readingProgress", snapshot.readingProgress)
                    .put("chapterIndex", snapshot.chapterIndex).put("readOffset", snapshot.readOffset)).toString()); put("created_at", now)
            })
            journal("note", id, bookId, before, snapshot(scopes), "note-save", now)
        }
    }

    /** Revalidate against the original chapter immediately before committing any AI result. */
    fun applyGenerated(bookId: Long, chunk: ReadingChunk, chapterText: String,
                       materials: List<GeneratedBookMaterial>, userAuthorized: Boolean,
                       sessionId: String, boundary: Double): List<String> {
        require(userAuthorized && sessionId.isNotBlank() && boundary.isFinite() && boundary in 0.0..1.0)
        require(materials.size <= 12 && chunk.endOffset <= chapterText.length)
        materials.forEach { material ->
            require(material.kind in BookWikiProjector.categories && material.title.isNotBlank() && material.title.length <= 300)
            require(material.kind != "wiki.memory" && material.summary.length <= 3000 && material.body.length <= 12000)
            if (material.kind == "fiction.relationship") {
                val relation = requireNotNull(material.relationship)
                require(relation.from.isNotBlank() && relation.to.isNotBlank() && relation.from != relation.to && relation.relation.isNotBlank())
                require(listOf(relation.from, relation.to, relation.relation).all { it.length <= 200 })
                require(material.evidence.exactText.contains(relation.from) && material.evidence.exactText.contains(relation.to))
            }
            val evidence = material.evidence
            require(evidence.sourceProgress in 0.0..boundary)
            require(ReadingEvidenceResolver().resolve(chapterText, evidence.exactText, chunk,
                evidence.chapterTitle, evidence.sourceProgress, evidence.chapterIndex) == evidence) { "Evidence is stale or invalid" }
        }
        return ReadingSql.transaction(db) {
            activeBook(bookId)
            materials.mapNotNull { material ->
                val id = "native:${readingContentHash("$bookId:${chunk.id}:${material.kind}:${material.title}:${material.evidence.startOffset}") }"
                val scopes = when {
                    material.kind == "note.suggestion" -> listOf(Scope("tb_reading_note_ai_batches", "id", id), Scope("tb_reading_note_ai_suggestions", "id", id))
                    material.kind.startsWith("fiction.") -> listOf(Scope("tb_reading_artifacts", "id", id))
                    else -> wikiScopes(id)
                }
                // Stable IDs make crash recovery idempotent and protect user corrections/unknown imported rows.
                if (ReadingSql.rows(db, scopes.lastOrNull { it.table != "tb_book_wiki_revisions" && it.table != "tb_book_wiki_entry_sources" }!!.table,
                        "id = ?", arrayOf(id)).isNotEmpty()) return@mapNotNull null
                val before = snapshot(scopes)
                val now = clock()
                val e = material.evidence
                when {
                    material.kind == "note.suggestion" -> {
                        val source = JSONObject().put("ref", chunk.id).put("href", e.chapterHref)
                            .put("chapter", e.chapterTitle).put("text", e.exactText).put("progress", e.sourceProgress)
                            .put("chapterIndex", e.chapterIndex).put("endOffset", e.endOffset)
                        db.insertOrThrow("tb_reading_note_ai_batches", null, ContentValues().apply {
                            put("id", id); put("book_id", bookId); put("scope", "native.reading.evidence")
                            put("source_snapshot", JSONArray().put(source).toString()); put("status", "pending")
                            put("total_count", 1); put("remaining_count", 1); put("created_at", now); put("updated_at", now)
                        })
                        db.insertOrThrow("tb_reading_note_ai_suggestions", null, ContentValues().apply {
                            put("id", id); put("batch_id", id); put("book_id", bookId); put("source_type", "native.reading.evidence")
                            put("source_ref", chunk.id); put("content_hash", chunk.contentHash)
                            put("suggested_title", material.title); put("suggested_body", material.body.ifBlank { material.summary })
                            put("created_at", now); put("updated_at", now)
                        })
                    }
                    material.kind.startsWith("fiction.") -> db.insertOrThrow("tb_reading_artifacts", null, ContentValues().apply {
                        put("id", id); put("book_id", bookId); put("module_id", "fiction"); put("artifact_kind", material.kind)
                        put("payload_json", JSONObject().put("title", material.title).put("summary", material.summary)
                            .put("contentMarkdown", material.body).apply { material.relationship?.let { relation ->
                                put("from", relation.from); put("to", relation.to); put("relation", relation.relation)
                            } }.apply { material.structure?.let { scope ->
                                put("workId", scope.workId); put("arcId", scope.arcId); put("volumeId", scope.volumeId); put("sceneId", scope.sceneId)
                            } }.put("nativeSource", JSONObject()
                                .put("chapterIndex", e.chapterIndex).put("endOffset", e.endOffset)).toString())
                        put("epistemic_status", "agentInference"); put("source_text_snapshot", e.exactText)
                        put("chapter_href", e.chapterHref); put("chapter_title", e.chapterTitle)
                        put("source_progress", e.sourceProgress); put("visible_from_progress", e.sourceProgress)
                        put("ingested_at", now); put("ingestion_mode", "backfill"); put("session_id", sessionId)
                        put("created_by", "agent"); put("created_at", now); put("updated_at", now)
                    })
                    else -> {
                        db.insertOrThrow("tb_book_wiki_entries", null, ContentValues().apply {
                            put("id", id); put("book_id", bookId); put("kind", material.kind); put("title", material.title)
                            put("summary", material.summary); put("content_markdown", material.body)
                            put("visible_from_progress", e.sourceProgress); put("epistemic_status", "agentInference")
                            put("sort_key", "${e.chapterHref}:${e.startOffset.toString().padStart(10, '0')}")
                            put("created_at", now); put("updated_at", now)
                        })
                        db.insertOrThrow("tb_book_wiki_entry_sources", null, ContentValues().apply {
                            put("id", "$id:source:${e.chapterIndex}:${e.endOffset}"); put("entry_id", id); put("book_id", bookId)
                            put("chapter_href", e.chapterHref); put("chapter_title", e.chapterTitle)
                            put("text_snapshot", e.exactText); put("source_progress", e.sourceProgress); put("created_at", now)
                        })
                    }
                }
                journal(if (material.kind.startsWith("fiction.")) "artifact" else if (material.kind == "note.suggestion") "note" else "wiki",
                    id, bookId, before, snapshot(scopes), sessionId, now)
            }
        }
    }

    fun correctWiki(bookId: Long, entryId: String, expectedVersion: Int, correction: String,
                    userAuthorized: Boolean): String {
        require(userAuthorized && correction.isNotBlank() && correction.length <= 12000)
        return editWiki(bookId, entryId, expectedVersion, "correction", correction)
    }
    fun hideWiki(bookId: Long, entryId: String, expectedVersion: Int, userAuthorized: Boolean): String {
        require(userAuthorized)
        return editWiki(bookId, entryId, expectedVersion, "hide", null)
    }
    private fun editWiki(bookId: Long, id: String, version: Int, kind: String, correction: String?): String = ReadingSql.transaction(db) {
        activeBook(bookId)
        val entry = ReadingSql.rows(db, "tb_book_wiki_entries", "id = ? AND book_id = ?", arrayOf(id, bookId.toString())).single()
        require(entry.getString("kind") in BookWikiProjector.categories && entry.getInt("version") == version && entry.getString("status") == "active")
        val scopes = wikiScopes(id)
        val before = snapshot(scopes)
        val now = clock()
        db.update("tb_book_wiki_entries", ContentValues().apply {
            put("version", version + 1); put("updated_at", now)
            if (correction == null) put("status", "hidden") else { put("content_markdown", correction); put("summary", correction.take(300)); put("user_corrected", 1) }
        }, "id = ?", arrayOf(id))
        db.insertOrThrow("tb_book_wiki_revisions", null, ContentValues().apply {
            put("id", UUID.randomUUID().toString()); put("entry_id", id); put("book_id", bookId); put("base_version", version)
            put("revision_kind", kind); put("correction", correction.orEmpty()); put("before_snapshot", entry.toString())
            put("after_snapshot", ReadingSql.rows(db, "tb_book_wiki_entries", "id = ?", arrayOf(id)).single().toString())
            put("device_id", "native"); put("created_at", now)
        })
        journal("wiki", id, bookId, before, snapshot(scopes), "wiki-edit", now)
    }

    fun reviewSuggestion(bookId: Long, id: String, accept: Boolean, userAuthorized: Boolean): String {
        require(userAuthorized)
        return ReadingSql.transaction(db) {
            activeBook(bookId)
            val row = ReadingSql.rows(db, "tb_reading_note_ai_suggestions",
                "id = ? AND book_id = ? AND source_type = ? AND status = 'pending'",
                arrayOf(id, bookId.toString(), "native.reading.evidence")).single()
            val visible = BookWikiRepository(store).materials(bookId, activeBook(bookId).getDouble("reading_percentage"))
                .firstOrNull { it.id == id } ?: error("Suggestion is beyond the current reading boundary")
            val suggestionScopes = listOf(Scope("tb_reading_note_ai_suggestions", "id", id), Scope("tb_reading_note_ai_batches", "id", row.getString("batch_id")))
            val before = snapshot(suggestionScopes)
            val now = clock()
            var scopes = suggestionScopes
            if (accept) {
                val note = ReadingNoteWorkspaceStore(store).createManual(bookId, row.getString("suggested_title"),
                    row.getString("suggested_body"), emptyList(), "active", false)
                val noteScopes = noteScopes(note)
                val emptyNote = snapshot(noteScopes, empty = true).getJSONArray("scopes")
                for (i in 0 until emptyNote.length()) before.getJSONArray("scopes").put(emptyNote.get(i))
                scopes = suggestionScopes + noteScopes
                db.update("tb_reading_notes", ContentValues().apply { put("capture_kind", "ai") }, "id = ?", arrayOf(note))
                db.update("tb_reading_note_blocks", ContentValues().apply { put("origin", "ai") }, "note_id = ?", arrayOf(note))
                val source = visible.sources.single()
                db.insertOrThrow("tb_reading_note_sources", null, ContentValues().apply {
                    put("note_id", note); put("source_type", "aiSuggestion"); put("source_ref", id)
                    put("chapter_href", source.chapterHref); put("chapter_title", source.chapterTitle)
                    put("text_snapshot", source.text); put("metadata", JSONObject().put("sourceProgress", source.progress).toString()); put("created_at", now)
                })
            }
            db.update("tb_reading_note_ai_suggestions", ContentValues().apply { put("status", if (accept) "applied" else "rejected"); put("updated_at", now) }, "id = ?", arrayOf(id))
            db.update("tb_reading_note_ai_batches", ContentValues().apply { put("status", "completed"); put("remaining_count", 0); put("updated_at", now) }, "id = ?", arrayOf(row.getString("batch_id")))
            journal("note", id, bookId, before, snapshot(scopes), "suggestion-review", now)
        }
    }

    fun recordWikiCoverage(bookId: Long, coverageEnd: Double, boundary: Double, sessionId: String, userAuthorized: Boolean): String? {
        require(userAuthorized && coverageEnd.isFinite() && boundary.isFinite() && coverageEnd in 0.0..boundary && boundary <= 1.0)
        return ReadingSql.transaction(db) {
            activeBook(bookId)
            val scope = listOf(Scope("tb_book_wikis", "book_id", bookId.toString()))
            val old = ReadingSql.rows(db, "tb_book_wikis", "book_id = ?", arrayOf(bookId.toString())).singleOrNull()
            if (old != null && (old.optString("generation_scope") != "read_boundary" ||
                    old.optString("status") !in setOf("empty", "partial", "ready", "failed"))) return@transaction null
            if (old != null && old.getDouble("coverage_end") >= coverageEnd) return@transaction null
            val before = snapshot(scope)
            val now = clock()
            val values = ContentValues().apply {
                put("book_id", bookId); put("version", (old?.optInt("version") ?: 0) + 1)
                put("generation_scope", "read_boundary"); put("safe_knowledge_boundary", boundary)
                put("coverage_start", 0.0); put("coverage_end", coverageEnd)
                put("status", if (coverageEnd == 1.0) "ready" else "partial")
                put("last_generated_at", now); put("updated_at", now)
            }
            if (old == null) db.insertOrThrow("tb_book_wikis", null, values)
            else db.update("tb_book_wikis", values, "book_id = ?", arrayOf(bookId.toString()))
            journal("wiki", bookId.toString(), bookId, before, snapshot(scope), sessionId, now)
        }
    }

    /** Freeze selected workspace IDs only after explicit confirmation; this never edits user notes. */
    fun beginNoteOrganization(bookId: Long, noteIds: List<String>, userAuthorized: Boolean): StoredReadingTask {
        require(userAuthorized && noteIds.isNotEmpty() && noteIds.size <= 100 && noteIds.distinct().size == noteIds.size)
        return ReadingSql.transaction(db) {
            val book = activeBook(bookId)
            val repository = NativeNoteOrganizationRepository(store)
            val notes = noteIds.map { repository.freeze(bookId, it) ?: error("Note is no longer editable") }
            require(notes.all { it.source.length <= 32_000 }) { "Select notes with at most 32,000 source characters each" }
            val batch = UUID.randomUUID().toString()
            val frozenSources = JSONArray().apply { notes.forEach { put(JSONObject(it.source)) } }
            val task = ReadingTaskRepository(store).create(bookId, NativeNoteOrganizationRepository.TASK_TYPE,
                JSONObject().put("nativeNoteVersion", 1).put("batchId", batch)
                    .put("sourceHash", ReadingSql.hash(frozenSources))
                    .put("notes", JSONArray().apply { notes.forEach { put(JSONObject().put("id", it.id).put("version", it.version)) } })
                    .put("bookTitle", book.getString("title")).put("bookAuthor", book.safeString("author")), true)
            db.insertOrThrow("tb_reading_note_ai_batches", null, ContentValues().apply {
                put("id", batch); put("book_id", bookId); put("scope", "native.existingNotes")
                put("source_snapshot", frozenSources.toString())
                put("status", "pending"); put("total_count", notes.size); put("remaining_count", notes.size)
                put("created_at", clock()); put("updated_at", clock())
            })
            task
        }
    }

    /** Returns the journal action ID for undo; its targetId identifies the pending suggestion to review. */
    fun storeNoteOrganizationProposal(bookId: Long, batchId: String, source: OrganizationNote,
        proposed: ReviewedNoteChange, result: AiRequestResult, userAuthorized: Boolean, sessionId: String): String {
        require(userAuthorized && source.bookId == bookId)
        val change = proposed.validate(source.id, source.version)
        return ReadingSql.transaction(db) {
            activeBook(bookId)
            val repository = NativeNoteOrganizationRepository(store)
            val task = ReadingTaskRepository(store).get(bookId, sessionId) ?: error("Organization task is missing")
            require(task.state.type == NativeNoteOrganizationRepository.TASK_TYPE && task.state.status == "running")
            val taskPayload = JSONObject(task.payload)
            require(taskPayload.getString("batchId") == batchId)
            val batchSources = repository.sources(bookId, batchId)
            require(ReadingSql.hash(JSONArray().apply { batchSources.forEach { put(JSONObject(it.source)) } }) == taskPayload.getString("sourceHash")) { "Frozen task sources changed" }
            val frozen = batchSources.single { it.id == source.id }
            require(ReadingSql.hash(JSONObject(frozen.source)) == ReadingSql.hash(JSONObject(source.source)) && repository.freeze(bookId, source.id)?.version == source.version) { "Source note changed" }
            require(ReadingSql.rows(db, "tb_reading_note_ai_suggestions", "batch_id = ? AND source_ref = ?",
                arrayOf(batchId, source.id)).isEmpty()) { "Proposal already saved" }
            val id = UUID.randomUUID().toString()
            val scopes = listOf(Scope("tb_reading_note_ai_batches", "id", batchId), Scope("tb_reading_note_ai_suggestions", "id", id))
            val before = snapshot(scopes)
            val now = clock()
            db.insertOrThrow("tb_reading_note_ai_suggestions", null, ContentValues().apply {
                put("id", id); put("batch_id", batchId); put("book_id", bookId)
                put("source_type", NativeNoteOrganizationRepository.SOURCE_TYPE); put("source_ref", source.id)
                put("content_hash", source.version); put("before_snapshot", source.source)
                put("suggested_title", change.title); put("suggested_body", change.body); put("suggested_tags", JSONArray(change.tags).toString())
                put("selected_fields", "[\"title\",\"body\",\"tags\"]"); put("status", "pending"); put("created_at", now); put("updated_at", now)
            })
            db.update("tb_reading_note_ai_batches", ContentValues().apply {
                put("provider_id", result.providerId); put("model", result.model); put("used_fallback", if (result.attempts > 1) 1 else 0)
                put("updated_at", now)
            }, "id = ?", arrayOf(batchId))
            journal("note", id, bookId, before, snapshot(scopes), sessionId, now)
        }
    }

    /** Accept edits the same workspace ID via its revision API; reject touches only review metadata. */
    fun reviewNoteOrganization(bookId: Long, suggestionId: String, accept: Boolean, userAuthorized: Boolean): String {
        require(userAuthorized)
        return ReadingSql.transaction(db) {
            activeBook(bookId)
            val row = ReadingSql.rows(db, "tb_reading_note_ai_suggestions",
                "id = ? AND book_id = ? AND source_type = ? AND status = 'pending'",
                arrayOf(suggestionId, bookId.toString(), NativeNoteOrganizationRepository.SOURCE_TYPE)).single()
            val repository = NativeNoteOrganizationRepository(store)
            val original = repository.decode(JSONObject(row.getString("before_snapshot")))
            require(original.bookId == bookId && original.id == row.getString("source_ref") && original.version == row.getString("content_hash"))
            val batchId = row.getString("batch_id")
            require(repository.sources(bookId, batchId).single { it.id == original.id }.version == original.version)
            val scopes = mutableListOf(Scope("tb_reading_note_ai_suggestions", "id", suggestionId), Scope("tb_reading_note_ai_batches", "id", batchId))
            if (accept) {
                require(repository.freeze(bookId, original.id)?.version == original.version) { "Source note changed; generate a fresh proposal" }
                scopes += noteScopes(original.id)
                // Workspace.save also updates a linked legacy annotation. Journal it with the document.
                val annotations = ReadingSql.rows(db, "tb_reading_note_sources", "note_id = ? AND source_type = 'annotation'", arrayOf(original.id))
                    .mapNotNull { it.safeString("source_ref").toLongOrNull() }.distinct().map { Scope("tb_notes", "id", it.toString()) }
                annotations.forEach { scope -> require(ReadingSql.rows(db, "tb_notes", "id = ? AND book_id = ?",
                    arrayOf(scope.value, bookId.toString())).size == 1) { "Linked annotation belongs to another book" } }
                scopes += annotations
            }
            val before = snapshot(scopes)
            if (accept) {
                val change = ReviewedNoteChange(original.id, original.version, row.getString("suggested_title"), row.getString("suggested_body"),
                    repository.strings(JSONArray(row.getString("suggested_tags")))).validate(original.id, original.version)
                val note = ReadingSql.rows(db, "tb_reading_notes", "id = ? AND book_id = ?", arrayOf(original.id, bookId.toString())).single()
                ReadingNoteWorkspaceStore(store).save(original.id, change.title, change.body, change.tags, note.getString("status"), note.getInt("is_favorite") != 0)
            }
            if (accept) {
                // Tag definitions are shared. Guard their contents, but undo restores only this note's memberships.
                val oldTags = before.getJSONArray("scopes")
                val oldTagIds = mutableSetOf<String>()
                for (i in 0 until oldTags.length()) {
                    val item = oldTags.getJSONObject(i)
                    if (item.getString("table") == "tb_reading_note_tags") {
                        val rows = item.getJSONArray("rows")
                        for (j in 0 until rows.length()) oldTagIds += rows.getJSONObject(j).getString("tag_id")
                    }
                }
                val tagIds = oldTagIds + ReadingSql.rows(db, "tb_reading_note_tags", "note_id = ?", arrayOf(original.id)).map { it.getString("tag_id") }
                val guards = tagIds.sorted().map { Scope("tb_reading_tags", "id", it, restore = false) }
                val guardRows = snapshot(guards).getJSONArray("scopes")
                for (i in 0 until guardRows.length()) before.getJSONArray("scopes").put(guardRows.get(i))
                scopes += guards
            }
            val now = clock()
            db.update("tb_reading_note_ai_suggestions", ContentValues().apply {
                put("status", if (accept) "applied" else "rejected"); put("updated_at", now)
                if (accept) put("applied_hash", repository.freeze(bookId, original.id)!!.version)
            }, "id = ?", arrayOf(suggestionId))
            val batch = ReadingSql.rows(db, "tb_reading_note_ai_batches", "id = ? AND book_id = ?", arrayOf(batchId, bookId.toString())).single()
            val remaining = (batch.getInt("remaining_count") - 1).coerceAtLeast(0)
            db.update("tb_reading_note_ai_batches", ContentValues().apply {
                put("remaining_count", remaining); put("status", if (remaining == 0) "completed" else "pending"); put("updated_at", now)
            }, "id = ?", arrayOf(batchId))
            journal("note", original.id, bookId, before, snapshot(scopes), "note-organization-review", now)
        }
    }

    fun completeOutcome(bookId: Long, expected: ReadingOutcome, context: ReadingContextSnapshot, userAuthorized: Boolean): String {
        require(userAuthorized && expected.bookId == bookId && expected.kind != ReadingOutcomeKind.CARD)
        return ReadingSql.transaction(db) {
            activeBook(bookId)
            require(NativeReadingOutcomeRepository(store).load(bookId, context).any { it == expected }) { "Outcome changed or source is no longer visible" }
            val table = when (expected.kind) {
                ReadingOutcomeKind.GOAL -> "tb_reading_goals"
                ReadingOutcomeKind.CHECKPOINT -> "tb_reading_checkpoints"
                ReadingOutcomeKind.DIFFICULTY -> "tb_reading_difficulties"
                ReadingOutcomeKind.CARD -> error("Card review requires a review rating")
            }
            require(expected.status == when (expected.kind) {
                ReadingOutcomeKind.GOAL -> "active"
                ReadingOutcomeKind.CHECKPOINT -> "pending"
                else -> "unresolved"
            })
            val scopes = listOf(Scope(table, "id", expected.id))
            val before = snapshot(scopes)
            val now = maxOf(clock(), expected.version + 1)
            check(db.update(table, ContentValues().apply {
                put("status", if (expected.kind == ReadingOutcomeKind.DIFFICULTY) "resolved" else "completed")
                put("updated_at", now)
                if (expected.kind == ReadingOutcomeKind.GOAL) put("progress", 1.0)
            }, "id = ? AND book_id = ? AND updated_at = ?", arrayOf(expected.id, bookId.toString(), expected.version.toString())) == 1)
            journal(if (expected.kind == ReadingOutcomeKind.GOAL) "goal" else if (expected.kind == ReadingOutcomeKind.DIFFICULTY) "difficulty" else "memory", expected.id, bookId, before, snapshot(scopes), "outcome-complete", clock())
        }
    }

    fun history(bookId: Long): List<ReadingActionRecord> = ReadingSql.rows(db, "tb_agent_actions",
        "book_id = ? AND session_id LIKE 'native.reading:%' AND expires_at >= ?", arrayOf(bookId.toString(), clock().toString()))
        .map { ReadingActionRecord(it.getString("id"), it.getString("target_id"), it.getString("status"), it.getLong("created_at"), actionTitle(it.safeString("after_snapshot"))) }
        .sortedByDescending { it.createdAt }

    fun undo(actionId: String): NativeReadingUndoResult = ReadingSql.transaction(db) {
        val action = ReadingSql.rows(db, "tb_agent_actions", "id = ?", arrayOf(actionId)).singleOrNull()
            ?: return@transaction NativeReadingUndoResult.MISSING
        if (!action.getString("session_id").startsWith("native.reading:")) return@transaction NativeReadingUndoResult.UNSUPPORTED
        if (action.getString("status") == "undone") return@transaction NativeReadingUndoResult.ALREADY_UNDONE
        if (clock() >= action.getLong("expires_at")) return@transaction NativeReadingUndoResult.EXPIRED
        val before = JSONObject(action.getString("before_snapshot"))
        val after = JSONObject(action.getString("after_snapshot"))
        val scopes = decodeScopes(after)
        if (action.getString("status") != "applied" || ReadingSql.hash(snapshot(scopes)) != action.getString("after_hash")) {
            db.update("tb_agent_actions", ContentValues().apply { put("status", "conflict") }, "id = ?", arrayOf(actionId))
            return@transaction NativeReadingUndoResult.CONFLICT
        }
        require(decodeScopes(before) == scopes)
        scopes.asReversed().filter { it.restore }.forEach { db.delete(it.table, "${it.key} = ?", arrayOf(it.value)) }
        val rows = before.getJSONArray("scopes")
        for (i in 0 until rows.length()) {
            val scope = scopes[i]
            if (!scope.restore) continue
            val records = rows.getJSONObject(i).getJSONArray("rows")
            for (j in 0 until records.length()) db.insertOrThrow(scope.table, null, ReadingSql.values(records.getJSONObject(j)))
        }
        db.update("tb_agent_actions", ContentValues().apply { put("status", "undone"); put("undone_at", clock()) }, "id = ?", arrayOf(actionId))
        NativeReadingUndoResult.UNDONE
    }

    private fun actionTitle(raw: String): String = runCatching {
        val scopes = JSONObject(raw).getJSONArray("scopes")
        for (i in 0 until scopes.length()) {
            val rows = scopes.getJSONObject(i).getJSONArray("rows")
            for (j in 0 until rows.length()) {
                val row = rows.getJSONObject(j)
                val title = row.safeString("title").ifBlank { row.safeString("suggested_title") }.ifBlank {
                    runCatching { JSONObject(row.safeString("payload_json")).safeString("title") }.getOrDefault("")
                }
                if (title.isNotBlank()) return@runCatching title
            }
        }
        ""
    }.getOrDefault("")

    private fun snapshot(scopes: List<Scope>, empty: Boolean = false) = JSONObject().put("nativeReadingVersion", 1).put("scopes", JSONArray().apply {
        scopes.forEach { scope -> put(JSONObject().put("table", scope.table).put("key", scope.key).put("value", scope.value)
            .apply { if (!scope.restore) put("restore", false) }.put("rows", ReadingSql.array(if (empty) emptyList() else ReadingSql.rows(db, scope.table, "${scope.key} = ?", arrayOf(scope.value))))) }
    })
    private fun decodeScopes(snapshot: JSONObject): List<Scope> {
        require(snapshot.getInt("nativeReadingVersion") == 1)
        val scopes = snapshot.getJSONArray("scopes")
        return (0 until scopes.length()).map { index ->
            val row = scopes.getJSONObject(index)
            val table = row.getString("table"); val key = row.getString("key")
            require(allowed[table] == key)
            Scope(table, key, row.getString("value"), row.optBoolean("restore", true)).also {
                require(it.table != "tb_reading_tags" || !it.restore)
            }
        }
    }
    private fun journal(type: String, target: String, bookId: Long, before: JSONObject,
                        after: JSONObject, session: String, now: Long): String {
        val id = UUID.randomUUID().toString()
        db.insertOrThrow("tb_agent_actions", null, ContentValues().apply {
            put("id", id); put("action_type", type); put("target_id", target); put("book_id", bookId)
            put("before_snapshot", before.toString()); put("after_snapshot", after.toString()); put("after_hash", ReadingSql.hash(after))
            put("status", "applied"); put("session_id", "native.reading:$session"); put("created_at", now)
            put("expires_at", now + 30L * 24 * 60 * 60 * 1000)
        })
        db.delete("tb_agent_actions", "session_id LIKE 'native.reading:%' AND expires_at < ?", arrayOf(now.toString()))
        db.execSQL("""DELETE FROM tb_agent_actions WHERE id IN (SELECT id FROM tb_agent_actions
            WHERE session_id LIKE 'native.reading:%' ORDER BY created_at DESC, rowid DESC LIMIT -1 OFFSET 200)""")
        return id
    }
}
