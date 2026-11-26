package com.aceinandroid.inkflow.core.ai

import kotlin.test.*

class NativeReadingDomainTest {
    @Test fun chunksPreserveUtf16SourceAndStableIdentity() {
        val text = "第一段落。\n\n" + "Sentence with a face 😀 and punctuation. ".repeat(12)
        val chunker = ReadingChunker(64)
        val chunks = chunker.chunk("chapter/1", text)
        assertEquals(text, chunks.joinToString("") { it.content })
        assertTrue(chunks.all { it.content.length <= 64 && it.content == text.substring(it.startOffset, it.endOffset) })
        assertTrue(chunks.none { it.content.last().isHighSurrogate() || it.content.first().isLowSurrogate() })
        assertEquals(chunks, chunker.chunk("chapter/1", text))
        assertNotEquals(chunks.first().id, ReadingChunker(64, "v2").chunk("chapter/1", text).first().id)
    }
    @Test fun evidenceReturnsOriginalSliceAfterWhitespaceAndWidthNormalization() {
        val text = "前言：ＡＢＣ，  原文\n\t证据。后记"
        val chunk = ReadingChunker().chunk("one", text).single()
        val evidence = ReadingEvidenceResolver().resolve(text, "「ABC, 原文 证据.」", chunk, "One", .3)!!
        assertEquals("ＡＢＣ，  原文\n\t证据。", evidence.exactText)
        assertEquals(evidence.exactText, text.substring(evidence.startOffset, evidence.endOffset))
    }
    @Test fun resolverNeverCrossesChunkOrAcceptsInventedEvidence() {
        val text = "Original evidence exists here.".padEnd(64, 'Z') + "Original evidence exists here."
        val chunks = ReadingChunker(64).chunk("one", text)
        val last = chunks.last()
        val resolver = ReadingEvidenceResolver()
        assertNull(resolver.resolve(text, "invented evidence exists here", last, "One", .5))
        assertNull(resolver.resolve(text, "Original evidence", last.copy(contentHash = "bad"), "One", .5))
        assertNull(resolver.resolve(text, "Orig", last, "One", Double.NaN))
        val matched = resolver.resolve(text, "exists here.", last, "One", .5)!!
        assertTrue(matched.startOffset >= last.startOffset)
    }
    @Test fun tinyOrTruncatedQuotesDoNotBecomeEvidence() {
        val text = "这是完整的原文依据。"
        val chunk = ReadingChunker().chunk("one", text).single()
        assertNull(ReadingEvidenceResolver().resolve(text, "原文", chunk, "One", .1))
        assertNull(ReadingEvidenceResolver().resolve(text, "这是…依据", chunk, "One", .1))
    }
    @Test fun unknownTaskRecordsSurviveRestorationUnchanged() {
        val state = ReadingTaskState("future.task", "running", progress = .3)
        val machine = ReadingTaskStateMachine()
        assertEquals(state, machine.restore(state))
        assertFailsWith<IllegalArgumentException> { machine.transition(state, ReadingTaskEvent.PAUSE) }
    }
    @Test fun restartPausesDurableTasksWithoutGrantingResumeAuthority() {
        val machine = ReadingTaskStateMachine()
        val state = ReadingTaskState("native.book.wiki.v1", "running", progress = .35)
        val restored = machine.restore(state)
        assertEquals("paused", restored.status)
        assertEquals(.35, restored.progress)
        assertFailsWith<IllegalArgumentException> { machine.transition(restored, ReadingTaskEvent.RESUME) }
        assertEquals("running", machine.transition(restored, ReadingTaskEvent.RESUME, true).status)
        assertEquals("cancelled", machine.restore(state.copy(durable = false)).status)
    }
    @Test fun completedTasksCannotResumeAndFailedTasksRequireExplicitRetry() {
        val machine = ReadingTaskStateMachine()
        val running = ReadingTaskState("native.book.atlas.v1", "running")
        val completed = machine.transition(running, ReadingTaskEvent.COMPLETE)
        assertEquals(1.0, completed.progress)
        assertFailsWith<IllegalArgumentException> { machine.transition(completed, ReadingTaskEvent.RESUME, true) }
        val failed = machine.transition(running, ReadingTaskEvent.FAIL)
        assertFailsWith<IllegalArgumentException> { machine.transition(failed, ReadingTaskEvent.RESUME) }
    }
    private fun material(id: String = "entry", boundary: Double = .3, kind: String = "wiki.chapter") = BookMaterial(
        id, 1, kind, "Title", "Summary", "Body", boundary, "agentInference",
        listOf(BookMaterialSource("chapter", "Chapter", "", "Exact original evidence", boundary)))
    @Test fun projectionsUseCurrentPositionAndRequireEverySourceWithinBoundary() {
        val projector = BookWikiProjector()
        val good = material()
        val future = material("future", .7)
        val unknown = material("unknown", kind = "future.kind")
        val missingSource = good.copy(id = "missing", sources = emptyList())
        val invalidSource = good.copy(id = "invalid", sources = good.sources.map { it.copy(progress = .8) })
        assertEquals(listOf(good), projector.visible(1, .4, listOf(good, future, unknown, missingSource, invalidSource)))
        assertTrue(projector.visible(1, .2, listOf(good)).isEmpty())
        assertTrue(projector.visible(2, 1.0, listOf(good)).isEmpty())
    }
    @Test fun sourcesWithNoPositionOrNonFiniteProgressAreHidden() {
        val good = material()
        val unanchored = good.copy(sources = good.sources.map { it.copy(chapterHref = "", cfi = "") })
        val nan = good.copy(visibleFromProgress = Double.NaN)
        assertTrue(BookWikiProjector().visible(1, 1.0, listOf(unanchored, nan)).isEmpty())
    }
    @Test fun contextPackKeepsBudgetSourceStatusAndConservativeIntegerBoundary() {
        val item = material(boundary = .301)
        val nodes = ReadingContextPackBuilder().build(1, .31, listOf(item), charBudget = 70)
        assertEquals(1, nodes.size)
        assertTrue(nodes.sumOf { it.text.length } <= 70)
        assertEquals(30, nodes.single().visibleFromProgress)
        assertEquals(.301, nodes.single().readingProgress)
        assertTrue(nodes.single().text.contains("agentInference"))
        assertTrue(ReadingContextPackBuilder().build(1, .3, listOf(item)).isEmpty())
        assertTrue(ReadingContextPackBuilder().build(1, 1.0, listOf(item), charBudget = 0).isEmpty())
    }
    @Test fun sameChapterRewindFiltersExactEndpointsEvenWhenPercentMatches() {
        val source = material().sources.single().copy(chapterIndex = 3, endOffset = 500)
        val earlier = material("earlier").copy(sources = listOf(source.copy(endOffset = 100)))
        val later = material("later").copy(sources = listOf(source))
        val unknown = material("unknown")
        assertEquals(listOf(earlier), BookWikiProjector().visible(1, .5,
            listOf(earlier, later, unknown), chapterIndex = 3, readOffset = 200))
        assertEquals(2, BookWikiProjector().visible(1, .5, listOf(earlier, later), chapterIndex = 4, readOffset = 0).size)
    }
    @Test fun timelineAndCharacterGroupsRequireRealSources() {
        val grouped = BookWikiProjector().grouped(1, .5,
            listOf(material(kind = "fiction.event"), material("character", kind = "fiction.character")))
        assertEquals(1, grouped[BookHubCategory.TIMELINE]?.size)
        assertEquals(1, grouped[BookHubCategory.CHARACTERS]?.size)
    }
    @Test fun noteOrganizationRequiresExactFrozenIdentityAndVersion() {
        val change = ReviewedNoteChange("existing", "v1", "Better title", "Original meaning", listOf("tag"))
        assertEquals(change, change.validate("existing", "v1"))
        assertFailsWith<IllegalArgumentException> { change.validate("other-note", "v1") }
        assertFailsWith<IllegalArgumentException> { change.validate("existing", "v2") }
    }
    @Test fun noteOrganizationRejectsOversizedAndAmbiguousFields() {
        val change = ReviewedNoteChange("id", "version", "Title", "Body", listOf("Tag"))
        assertFailsWith<IllegalArgumentException> { change.copy(tags = listOf("Tag", " tag ")).validate("id", "version") }
        assertFailsWith<IllegalArgumentException> { change.copy(body = "x".repeat(24_001)).validate("id", "version") }
        assertFailsWith<IllegalArgumentException> { change.copy(title = " ").validate("id", "version") }
    }
    @Test fun noteOrganizationTaskRestoresPausedAndNeedsExplicitResume() {
        val state = ReadingTaskState("native.notes.organize.v1", "running", progress = .5)
        val machine = ReadingTaskStateMachine()
        val paused = machine.restore(state)
        assertEquals("paused", paused.status)
        assertEquals(.5, paused.progress)
        assertFailsWith<IllegalArgumentException> { machine.transition(paused, ReadingTaskEvent.RESUME) }
        assertEquals("running", machine.transition(paused, ReadingTaskEvent.RESUME, true).status)
    }
    @Test fun relationshipGraphUsesOnlyExplicitSourcedEdges() {
        val relationship = material("edge", kind = "fiction.relationship").copy(relationship = BookRelationship("Alice", "Bob", "ally"))
        val graph = ReadingRelationshipGraph().project(1, .5, listOf(relationship,
            material("Alice", kind = "fiction.character"), material("Bob", kind = "fiction.character")))
        assertEquals(listOf("Alice", "Bob"), graph.nodes)
        assertEquals(listOf(relationship), graph.edges)
        assertTrue(ReadingRelationshipGraph().project(1, .5, listOf(relationship.copy(relationship = null))).edges.isEmpty())
        assertTrue(ReadingRelationshipGraph().project(1, .5, listOf(relationship.copy(sources = emptyList()))).edges.isEmpty())
    }
    @Test fun relationshipGraphHonorsExactRewindAndUnknownKinds() {
        val edge = material("edge", kind = "fiction.relationship").copy(relationship = BookRelationship("A", "B", "family"),
            sources = material().sources.map { it.copy(chapterIndex = 3, endOffset = 500) })
        val projector = ReadingRelationshipGraph()
        assertTrue(projector.project(1, .5, listOf(edge), 3, 200).edges.isEmpty())
        assertEquals(1, projector.project(1, .5, listOf(edge), 3, 500).edges.size)
        assertTrue(projector.project(1, .5, listOf(edge.copy(kind = "future.relationship"))).edges.isEmpty())
        assertTrue(projector.project(1, .5, listOf(edge.copy(relationship = BookRelationship("A", "A", "self")))).edges.isEmpty())
    }
    @Test fun structureExcludesPublishingMetadataButKeepsPrologueAndStableScopes() {
        val parser = ReadingStructureParser()
        val rows = parser.parse(listOf(
            ReadingStructureChapter("cover", "版权信息"), ReadingStructureChapter("work", "Collected story", hasChildren = true),
            ReadingStructureChapter("v1", "第一卷"), ReadingStructureChapter("case", "第一案"),
            ReadingStructureChapter("chapter", "第一回", tocDepth = 1), ReadingStructureChapter("prologue", "序章", tocDepth = 1)))
        assertEquals(ReadingChapterSemanticKind.FRONT_MATTER, rows.first().kind)
        assertEquals(ReadingChapterSemanticKind.NARRATIVE, rows.last().kind)
        assertNotNull(rows[1].workId); assertNotNull(rows[2].volumeId); assertNotNull(rows[3].arcId)
        assertEquals(rows[3].arcId, rows[4].arcId)
        assertEquals(rows, parser.parse(rows.map { it.chapter }))
    }
    @Test fun ordinaryNumberedChaptersNeverCreateCasesOrWorks() {
        val rows = ReadingStructureParser().parse(listOf(ReadingStructureChapter("one", "第一章", hasChildren = true), ReadingStructureChapter("two", "第二回", hasChildren = true)))
        assertTrue(rows.all { it.arcId == null && it.workId == null })
        assertNotEquals(rows[0].sceneId, rows[1].sceneId)
    }
    @Test fun atlasHasFourViewsThreeDensitiesAndLocalChapterCollapse() {
        val event = material("event", kind = "fiction.event").copy(story = StoryAtlasMetadata(listOf("A"), "major", "opening", workId = "work", arcId = "case"))
        val scene = material("scene", kind = "fiction.scene")
        val clue = material("clue", kind = "fiction.clue").copy(story = StoryAtlasMetadata(mysteryIds = listOf("mystery")))
        val relation = material("relation", kind = "fiction.relationship").copy(relationship = BookRelationship("A", "B", "ally"))
        val all = listOf(event, scene, clue, relation)
        val projector = StoryAtlasProjector()
        fun count(view: StoryTimelineView, density: StoryTimelineDensity = StoryTimelineDensity.COMPLETE) =
            projector.timeline(1, .5, all, view, density).sumOf { it.events.size }
        assertEquals(3, count(StoryTimelineView.STORY))
        assertEquals(1, count(StoryTimelineView.MYSTERY))
        assertEquals(2, count(StoryTimelineView.CHARACTER))
        assertEquals(1, count(StoryTimelineView.RELATIONSHIP))
        assertEquals(2, count(StoryTimelineView.STORY, StoryTimelineDensity.COMPACT))
        assertEquals(2, count(StoryTimelineView.STORY, StoryTimelineDensity.STANDARD))
        val chapter = projector.timeline(1, .5, all).first()
        assertTrue(projector.timeline(1, .5, all, collapsedChapters = setOf(chapter.id)).first().collapsed)
        assertEquals(listOf(event), projector.timeline(1, .5, all, workId = "work", arcId = "case").flatMap { it.events })
        assertEquals(listOf(clue), projector.cluesFor(1, .5, "mystery", all))
        assertTrue(projector.cluesFor(1, .1, "mystery", all).isEmpty())
    }
    @Test fun closurePoliciesAreExplicitAndNextActionsRespectPriorityEvidenceAndRewind() {
        val registry = ClosurePolicyRegistry()
        assertNull(registry.find("future.policy")); assertEquals(3, registry.policies.size)
        val source = material().sources.single().copy(chapterIndex = 2, endOffset = 500)
        val goal = ReadingOutcome("goal", 1, ReadingOutcomeKind.GOAL, "Goal", "active", 1)
        val checkpoint = ReadingOutcome("checkpoint", 1, ReadingOutcomeKind.CHECKPOINT, "Chapter", "pending", 1, source)
        val card = ReadingOutcome("card", 1, ReadingOutcomeKind.CARD, "Recall", "active", 1, source, dueAt = 10)
        val resolver = NextReadingActionResolver()
        assertEquals(card, resolver.resolve(1, registry.find("knowledge.argument")!!, listOf(goal, checkpoint, card), 10).outcome)
        assertEquals(checkpoint, resolver.resolve(1, registry.find("psychology.reflection")!!, listOf(goal, checkpoint, card), 10).outcome)
        assertEquals(goal, resolver.resolve(1, registry.find("fiction.immersion")!!, listOf(goal, checkpoint, card), 10).outcome)
        assertEquals(goal, resolver.resolve(1, registry.find("knowledge.argument")!!, listOf(goal, card), 10, .5, 2, 200).outcome)
        assertNull(resolver.resolve(2, registry.find("knowledge.argument")!!, listOf(goal, card), 10).outcome)
        assertNull(resolver.resolve(1, registry.find("knowledge.argument")!!, listOf(card.copy(source = null)), 10).outcome)
    }
}
