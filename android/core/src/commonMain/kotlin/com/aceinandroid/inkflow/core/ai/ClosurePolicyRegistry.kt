package com.aceinandroid.inkflow.core.ai

enum class ReadingOutcomeKind { GOAL, CHECKPOINT, DIFFICULTY, CARD }
data class ReadingOutcome(val id: String, val bookId: Long, val kind: ReadingOutcomeKind, val title: String,
    val status: String, val version: Long, val source: BookMaterialSource? = null, val dueAt: Long? = null)
data class ReadingClosurePolicy(val id: String, val immersive: Boolean, val order: List<ReadingOutcomeKind>)
class ClosurePolicyRegistry {
    val policies = listOf(
        ReadingClosurePolicy("fiction.immersion", true, listOf(ReadingOutcomeKind.GOAL, ReadingOutcomeKind.DIFFICULTY)),
        ReadingClosurePolicy("knowledge.argument", false, listOf(ReadingOutcomeKind.CARD, ReadingOutcomeKind.CHECKPOINT, ReadingOutcomeKind.DIFFICULTY, ReadingOutcomeKind.GOAL)),
        ReadingClosurePolicy("psychology.reflection", false, listOf(ReadingOutcomeKind.CHECKPOINT, ReadingOutcomeKind.DIFFICULTY, ReadingOutcomeKind.CARD, ReadingOutcomeKind.GOAL)),
    )
    fun find(id: String): ReadingClosurePolicy? = policies.firstOrNull { it.id == id }
}
data class NextReadingAction(val policyId: String, val outcome: ReadingOutcome? = null)
class NextReadingActionResolver {
    /** This local recommendation never calls a model or marks an outcome completed. */
    fun resolve(bookId: Long, policy: ReadingClosurePolicy, outcomes: List<ReadingOutcome>, now: Long,
                boundary: Double = 1.0, chapterIndex: Int = -1, readOffset: Int = -1): NextReadingAction {
        val safe = outcomes.filter { outcome -> outcome.source?.let { source ->
            BookWikiProjector().visible(bookId, boundary, listOf(BookMaterial(outcome.id, outcome.bookId, "wiki.memory",
                outcome.title, "", "", source.progress, "userReflection", listOf(source))), chapterIndex, readOffset).isNotEmpty()
        } ?: (outcome.kind == ReadingOutcomeKind.GOAL) }
        for (kind in policy.order) {
            val candidate = safe.filter { it.bookId == bookId && it.kind == kind && when (kind) {
                ReadingOutcomeKind.GOAL -> it.status == "active"
                ReadingOutcomeKind.CHECKPOINT -> it.status == "pending" && it.source != null
                ReadingOutcomeKind.DIFFICULTY -> it.status == "unresolved" && it.source != null
                ReadingOutcomeKind.CARD -> it.status == "active" && it.source != null && it.dueAt != null && it.dueAt <= now
            } }.sortedWith(compareBy<ReadingOutcome> { it.dueAt ?: Long.MAX_VALUE }.thenBy { it.id }).firstOrNull()
            if (candidate != null) return NextReadingAction(policy.id, candidate)
        }
        return NextReadingAction(policy.id)
    }
}
