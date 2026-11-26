package com.aceinandroid.inkflow

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

internal data class NativeVocabularyItem(
    val id: String,
    val word: String,
    val phonetic: String?,
    val definitionCn: String?,
    val definitionEn: String?,
    val bookTitle: String?,
    val chapterTitle: String?,
    val sourceSentence: String,
    val contextualDefinition: String?,
    val exampleSentence: String?,
    val reviewStage: Int,
    val nextReviewAt: Long,
    val familiarity: String,
    val correctCount: Int,
    val wrongCount: Int,
    val isMastered: Boolean,
    val position: String?,
    val bookId: String,
    val partOfSpeech: String?,
    val audioUrl: String?,
) {
    fun isDue(now: Long = System.currentTimeMillis()): Boolean = !isMastered && nextReviewAt <= now
}

internal enum class VocabularyReview { HARD, FAMILIAR, MASTERED }

internal class NativeVocabularyStore(private val books: BookStore) {
    private val database get() = books.writableDatabase

    fun all(): List<NativeVocabularyItem> = database.query("tb_vocabulary", COLUMNS,
        null, null, null, null, "created_at DESC").use { rows ->
        buildList {
            while (rows.moveToNext()) add(NativeVocabularyItem(
                id = rows.getString(0), word = rows.getString(1), phonetic = rows.getString(2),
                definitionCn = rows.getString(3), definitionEn = rows.getString(4),
                bookTitle = rows.getString(5), chapterTitle = rows.getString(6),
                sourceSentence = rows.getString(7).orEmpty(),
                contextualDefinition = rows.getString(8), exampleSentence = rows.getString(9),
                reviewStage = rows.getInt(10),
                nextReviewAt = bookTime(rows.getString(11).orEmpty())
                    .takeIf { it != Long.MIN_VALUE } ?: System.currentTimeMillis(),
                familiarity = rows.getString(12).orEmpty(), correctCount = rows.getInt(13),
                wrongCount = rows.getInt(14), isMastered = rows.getInt(15) != 0,
                position = rows.getString(16), bookId = rows.getString(17).orEmpty(),
                partOfSpeech = rows.getString(18), audioUrl = rows.getString(19),
            ))
        }
    }

    fun add(book: StoredBook, chapterTitle: String, word: String, sentence: String,
            position: String, definition: String,
            dictionary: NativeDictionaryEntry? = null): Boolean {
        val cleanWord = word.trim()
        require(cleanWord.isNotEmpty() && cleanWord.length <= 80) { "Invalid word" }
        val normalized = normalizeWord(cleanWord)
        require(normalized.isNotEmpty()) { "Invalid word" }
        val entry = dictionary?.takeIf { it.word == NativeEnglishDictionary.lookupWord(cleanWord) }
        val now = isoNow()
        val values = ContentValues().apply {
            put("id", UUID.randomUUID().toString())
            put("word", cleanWord)
            put("normalized_word", normalized)
            put("definition_cn", definition.trim().takeIf(String::isNotBlank))
            put("lemma", entry?.word)
            put("phonetic", entry?.phonetic)
            put("audio_url", entry?.audioUrl)
            put("part_of_speech", entry?.partOfSpeech)
            put("definition_en", entry?.definitionEn)
            put("example_sentence", entry?.exampleSentence)
            put("book_id", book.id.toString())
            put("book_title", book.title)
            put("chapter_title", chapterTitle)
            put("source_sentence", sentence.take(1000))
            put("position", position)
            put("review_stage", 0)
            put("next_review_at", now)
            put("familiarity", "newWord")
            put("correct_count", 0)
            put("wrong_count", 0)
            put("is_mastered", 0)
            put("created_at", now)
            put("updated_at", now)
        }
        return database.insertWithOnConflict("tb_vocabulary", null, values,
            SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    fun enrich(entry: NativeDictionaryEntry) {
        val db = database
        db.beginTransaction()
        try {
            db.query("tb_vocabulary", arrayOf("id", "lemma", "phonetic", "audio_url",
                "part_of_speech", "definition_en", "example_sentence"),
                "normalized_word = ?", arrayOf(entry.word), null, null, null).use { rows ->
                if (!rows.moveToFirst()) {
                    db.setTransactionSuccessful()
                    return
                }
                val values = ContentValues()
                val candidates = listOf("lemma" to entry.word, "phonetic" to entry.phonetic,
                    "audio_url" to entry.audioUrl, "part_of_speech" to entry.partOfSpeech,
                    "definition_en" to entry.definitionEn,
                    "example_sentence" to entry.exampleSentence)
                candidates.forEachIndexed { index, (column, value) ->
                    if (rows.getString(index + 1).isNullOrBlank() && !value.isNullOrBlank())
                        values.put(column, value)
                }
                if (values.size() > 0) {
                    values.put("updated_at", isoNow())
                    db.update("tb_vocabulary", values, "id = ?", arrayOf(rows.getString(0)))
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun containsNormalizedWord(word: String): Boolean = database.query("tb_vocabulary",
        arrayOf("id"), "normalized_word = ?", arrayOf(word), null, null, null, "1").use {
        it.moveToFirst()
    }

    fun review(id: String, result: VocabularyReview, now: Long = System.currentTimeMillis()) {
        val db = database
        db.beginTransaction()
        try {
            val state = db.query("tb_vocabulary", arrayOf("review_stage", "correct_count", "wrong_count"),
                "id = ?", arrayOf(id), null, null, null).use { rows ->
                require(rows.moveToFirst()) { "Word is unavailable" }
                Triple(rows.getInt(0), rows.getInt(1), rows.getInt(2))
            }
            val stage = when (result) {
                VocabularyReview.HARD -> state.first
                VocabularyReview.FAMILIAR -> (state.first + 1).coerceIn(1, 5)
                VocabularyReview.MASTERED -> 5
            }
            val days = when (result) {
                VocabularyReview.HARD -> 1
                VocabularyReview.FAMILIAR -> intArrayOf(1, 3, 7, 14, 30)[stage - 1]
                VocabularyReview.MASTERED -> 365
            }
            val changed = db.update("tb_vocabulary", ContentValues().apply {
                put("review_stage", stage)
                put("familiarity", when (result) {
                    VocabularyReview.HARD -> "hard"
                    VocabularyReview.FAMILIAR -> "familiar"
                    VocabularyReview.MASTERED -> "mastered"
                })
                put("correct_count", state.second + if (result == VocabularyReview.HARD) 0 else 1)
                put("wrong_count", state.third + if (result == VocabularyReview.HARD) 1 else 0)
                put("is_mastered", if (result == VocabularyReview.MASTERED) 1 else 0)
                put("last_reviewed_at", isoAt(now))
                put("next_review_at", isoAt(now + days * DAY_MS))
                put("updated_at", isoAt(now))
            }, "id = ?", arrayOf(id))
            check(changed == 1) { "Word is unavailable" }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun remove(id: String) {
        require(database.delete("tb_vocabulary", "id = ?", arrayOf(id)) == 1) {
            "Word is unavailable"
        }
    }

    private fun normalizeWord(word: String): String {
        val lower = word.trim().lowercase(Locale.ROOT)
        val cleaned = lower.replace(Regex("^[^a-z0-9]+|[^a-z0-9]+$"), "")
            .replace(Regex("\\s+"), " ")
        return cleaned.ifEmpty { lower }
    }

    private fun isoNow(): String = isoAt(System.currentTimeMillis())

    private fun isoAt(time: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(time))

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        val COLUMNS = arrayOf("id", "word", "phonetic", "definition_cn", "definition_en",
            "book_title", "chapter_title", "source_sentence", "contextual_definition",
            "example_sentence", "review_stage", "next_review_at", "familiarity",
            "correct_count", "wrong_count", "is_mastered", "position", "book_id",
            "part_of_speech", "audio_url")
    }
}
