package com.aceinandroid.inkflow.core.ai

enum class StoryTimelineView { STORY, MYSTERY, CHARACTER, RELATIONSHIP }
enum class StoryTimelineDensity { COMPACT, STANDARD, COMPLETE }
data class StoryAtlasMetadata(val participants: List<String> = emptyList(), val importance: String = "",
    val stage: String = "", val track: String = "", val workId: String? = null, val arcId: String? = null,
    val mysteryIds: List<String> = emptyList())
data class StoryTimelineChapter(val id: String, val title: String, val events: List<BookMaterial>, val collapsed: Boolean)

/** Filters exact source boundaries before scope, view, density or chapter grouping. No invented story time. */
class StoryAtlasProjector {
    fun timeline(bookId: Long, boundary: Double, materials: List<BookMaterial>,
        view: StoryTimelineView = StoryTimelineView.STORY, density: StoryTimelineDensity = StoryTimelineDensity.STANDARD,
        participant: String? = null, workId: String? = null, arcId: String? = null,
        collapsedChapters: Set<String> = emptySet(), chapterIndex: Int = -1, readOffset: Int = -1): List<StoryTimelineChapter> {
        val visible = BookWikiProjector().visible(bookId, boundary, materials, chapterIndex, readOffset)
        val events = visible.filter { item ->
            val meta = item.story
            val timelineKind = item.kind in setOf("fiction.event", "fiction.scene", "fiction.mystery", "fiction.clue", "fiction.relationship", "wiki.event")
            timelineKind && (workId == null || meta?.workId == workId) && (arcId == null || meta?.arcId == arcId) &&
                (participant.isNullOrBlank() || participant in meta?.participants.orEmpty() ||
                    item.relationship?.let { it.from == participant || it.to == participant } == true) &&
                when (view) {
                    StoryTimelineView.STORY -> item.kind != "fiction.relationship"
                    StoryTimelineView.MYSTERY -> item.kind in setOf("fiction.mystery", "fiction.clue")
                    StoryTimelineView.CHARACTER -> meta?.participants?.isNotEmpty() == true || item.relationship != null
                    StoryTimelineView.RELATIONSHIP -> item.kind == "fiction.relationship" && item.relationship != null
                } && when (density) {
                    StoryTimelineDensity.COMPLETE -> true
                    StoryTimelineDensity.STANDARD -> item.kind != "fiction.scene"
                    StoryTimelineDensity.COMPACT -> meta?.importance == "major" || item.kind in setOf("fiction.mystery", "fiction.clue", "fiction.relationship")
                }
        }
        return events.groupBy { it.sources.first().chapterHref.ifBlank { it.sources.first().cfi } }.map { (id, rows) ->
            StoryTimelineChapter(id, rows.first().sources.first().chapterTitle, rows, id in collapsedChapters)
        }
    }
    /** Explicit mystery identifiers link clues; shared names or text never establish a link. */
    fun cluesFor(bookId: Long, boundary: Double, mysteryId: String, materials: List<BookMaterial>, chapterIndex: Int = -1, readOffset: Int = -1): List<BookMaterial> =
        BookWikiProjector().visible(bookId, boundary, materials, chapterIndex, readOffset).filter {
        it.kind == "fiction.clue" && mysteryId in it.story?.mysteryIds.orEmpty()
    }
}
