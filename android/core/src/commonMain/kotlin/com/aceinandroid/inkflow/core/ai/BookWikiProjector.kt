package com.aceinandroid.inkflow.core.ai

data class BookMaterialSource(val chapterHref: String, val chapterTitle: String, val cfi: String,
                              val text: String, val progress: Double, val chapterIndex: Int = -1, val endOffset: Int = -1)
data class BookMaterial(
    val id: String, val bookId: Long, val kind: String, val title: String, val summary: String,
    val body: String, val visibleFromProgress: Double, val epistemicStatus: String,
    val sources: List<BookMaterialSource>, val version: Int = 1, val status: String = "active",
    val userCorrected: Boolean = false,
    val relationship: BookRelationship? = null,
    val story: StoryAtlasMetadata? = null,
)
enum class BookHubCategory { CHAPTERS, CHARACTERS, TIMELINE, CONCEPTS, RECORDS }

class BookWikiProjector {
    companion object {
        val categories = mapOf(
            "wiki.overview" to BookHubCategory.CHAPTERS, "wiki.chapter" to BookHubCategory.CHAPTERS,
            "wiki.part" to BookHubCategory.CHAPTERS,
            "wiki.character" to BookHubCategory.CHARACTERS, "wiki.relationship" to BookHubCategory.CHARACTERS,
            "fiction.character" to BookHubCategory.CHARACTERS, "fiction.relationship" to BookHubCategory.CHARACTERS,
            "wiki.event" to BookHubCategory.TIMELINE, "fiction.event" to BookHubCategory.TIMELINE,
            "fiction.scene" to BookHubCategory.TIMELINE, "fiction.clue" to BookHubCategory.TIMELINE,
            "fiction.mystery" to BookHubCategory.TIMELINE,
            "wiki.concept" to BookHubCategory.CONCEPTS, "wiki.argument" to BookHubCategory.CONCEPTS,
            "wiki.method" to BookHubCategory.CONCEPTS, "wiki.theme" to BookHubCategory.CONCEPTS,
            "wiki.question" to BookHubCategory.CONCEPTS,
            "wiki.memory" to BookHubCategory.RECORDS, "note.suggestion" to BookHubCategory.RECORDS,
        )
    }
    fun visible(bookId: Long, boundary: Double, materials: List<BookMaterial>, chapterIndex: Int = -1, readOffset: Int = -1): List<BookMaterial> {
        require(boundary.isFinite() && boundary in 0.0..1.0)
        return materials.filter { item ->
            item.bookId == bookId && item.kind in categories && item.status == "active" &&
                item.epistemicStatus in setOf("textFact", "userReflection", "agentInference", "externalFact") &&
                item.visibleFromProgress.isFinite() && item.visibleFromProgress in 0.0..boundary &&
                item.sources.isNotEmpty() && item.sources.all { source ->
                    source.text.isNotBlank() && (source.chapterHref.isNotBlank() || source.cfi.isNotBlank()) &&
                        source.progress.isFinite() && source.progress in 0.0..item.visibleFromProgress &&
                        (chapterIndex < 0 || (source.chapterIndex in 0..chapterIndex &&
                            (source.chapterIndex < chapterIndex || (readOffset >= 0 && source.endOffset in 0..readOffset))))
                }
        }.sortedWith(compareBy<BookMaterial> { it.visibleFromProgress }.thenBy { it.id })
    }
    fun grouped(bookId: Long, boundary: Double, materials: List<BookMaterial>): Map<BookHubCategory, List<BookMaterial>> =
        visible(bookId, boundary, materials).groupBy { categories.getValue(it.kind) }
}

/** Explicit payload endpoints only. Co-occurrence of characters never creates an edge. */
data class BookRelationship(val from: String, val to: String, val relation: String)
data class SourcedRelationshipGraph(val nodes: List<String>, val edges: List<BookMaterial>, val characters: List<BookMaterial> = emptyList())
class ReadingRelationshipGraph {
    fun project(bookId: Long, boundary: Double, materials: List<BookMaterial>, chapterIndex: Int = -1,
                readOffset: Int = -1): SourcedRelationshipGraph {
        val edges = BookWikiProjector().visible(bookId, boundary, materials, chapterIndex, readOffset).filter { item ->
            val edge = item.relationship
            item.kind == "fiction.relationship" && edge != null && edge.from.isNotBlank() && edge.to.isNotBlank() &&
                edge.from != edge.to && edge.relation.isNotBlank() &&
                listOf(edge.from, edge.to, edge.relation).all { it.length <= 200 }
        }
        val nodes = edges.flatMap { listOf(it.relationship!!.from, it.relationship.to) }.distinct()
        val characters = BookWikiProjector().visible(bookId, boundary, materials, chapterIndex, readOffset).filter {
            it.kind == "fiction.character" && it.title in nodes
        }
        return SourcedRelationshipGraph(nodes, edges, characters)
    }
}
