package com.aceinandroid.inkflow.core.ai

import kotlin.test.*

class AiCitationContractTest {
    private val source = BookMaterialSource("chapter", "Chapter", "", "Exact quote", .2, 0, 11)
    private val citation = AiCitation("stable:0", 7, source)
    @Test fun onlyKnownExactMarkersBecomeCitations() {
        assertEquals(listOf(citation), AiCitationMarkers.referenced(
            "Answer [[source:stable:0]] repeated [[source:stable:0]] forged [[source:other]]", listOf(citation)))
        assertTrue(AiCitationMarkers.referenced("Answer [stable:0]", listOf(citation)).isEmpty())
        assertTrue(AiCitationMarkers.referenced("[[source:<script>]]", listOf(citation)).isEmpty())
    }
    @Test fun stableSourceIdsSurviveProjectionWhileUnreadDataRemainsHidden() {
        val read = BookMaterial("stable", 7, "wiki.chapter", "Read", "Summary", "Body", .2, "textFact", listOf(source))
        val unread = read.copy(id = "future", visibleFromProgress = .9, sources = listOf(source.copy(progress = .9)))
        val builder = ReadingContextPackBuilder()
        val pack = builder.build(7, .3, listOf(read, unread))
        assertEquals(1, pack.size)
        assertEquals(citation, pack.single().citations.single())
        assertTrue(pack.single().text.contains("source:stable:0"))
        assertFalse(pack.single().text.contains("future"))
        assertEquals(pack, builder.build(7, .3, listOf(read, unread)))
    }
}
