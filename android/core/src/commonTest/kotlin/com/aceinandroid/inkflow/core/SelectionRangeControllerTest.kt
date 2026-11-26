package com.aceinandroid.inkflow.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelectionRangeControllerTest {
    @Test fun rejectsLateCommandsAndKeepsSessionForAdjustments() {
        val controller = SelectionRangeController("First sentence. Second sentence.")
        val old = controller.begin(TextSelectionRange(0, 5))!!
        val newer = controller.begin(TextSelectionRange(16, 22))!!
        assertNull(controller.replace(TextSelectionRange(0, 1), old.revision))
        assertNull(controller.adjust(SelectionRangeController.Endpoint.END,
            SelectionRangeController.Unit.CHARACTER, true, old.revision))
        val adjusted = controller.adjust(SelectionRangeController.Endpoint.END,
            SelectionRangeController.Unit.SENTENCE, true, newer.revision)!!
        assertEquals(newer.session, adjusted.session)
        assertEquals(32, adjusted.range.end)
        assertTrue(adjusted.revision > newer.revision)
    }

    @Test fun graphemesStayWholeAndEndpointsNeverCross() {
        val text = "Ae\u0301👩‍💻🇨🇳Z"
        val controller = SelectionRangeController(text)
        var state = controller.begin(TextSelectionRange(0, 1))!!
        val expected = listOf(3, 8, 12, 13)
        expected.forEach { end ->
            state = controller.adjust(SelectionRangeController.Endpoint.END,
                SelectionRangeController.Unit.CHARACTER, true, state.revision)!!
            assertEquals(end, state.range.end)
        }
        repeat(20) {
            state = controller.adjust(SelectionRangeController.Endpoint.START,
                SelectionRangeController.Unit.CHARACTER, true, state.revision)!!
            assertFalse(state.range.isEmpty)
        }
        assertEquals(TextSelectionRange(12, 13), state.range)
        assertEquals(TextSelectionRange(1, 3), controller.begin(TextSelectionRange(2, 3))!!.range)
    }

    @Test fun explicitExtensionRetractsAndCannotJumpPagesOrChapters() {
        val controller = SelectionRangeController("One. Two. Three. Four.")
        val original = controller.begin(TextSelectionRange(0, 4), visiblePage = 0)!!
        assertNull(controller.extendToPage(PageSlice(2, 10, 16, 2, 2), original.revision))
        val expanded = controller.extendToPage(PageSlice(1, 5, 10, 1, 1), original.revision)!!
        assertEquals(TextSelectionRange(0, 10), expanded.range)
        assertTrue(expanded.canRetract)
        assertNull(controller.extendToPage(PageSlice(2, 10, 999, 2, 2), expanded.revision))
        assertNull(controller.retract(original.revision))
        val restored = controller.retract(expanded.revision)!!
        assertEquals(original.range, restored.range)
        assertEquals(0, restored.visiblePage)
        assertFalse(restored.canRetract)
        controller.clear()
        assertNull(controller.replace(original.range, restored.revision))
    }

    @Test fun adjustedEndMustBeShownBeforeFurtherPageExtension() {
        val controller = SelectionRangeController("One. Two. Three.")
        val selected = controller.begin(TextSelectionRange(0, 10), visiblePage = 0)!!
        assertNull(controller.extendToPage(PageSlice(2, 10, 16, 2, 2), selected.revision))
        assertNull(controller.confirmVisiblePage(PageSlice(0, 0, 5, 0, 0), selected.revision))
        val visible = controller.confirmVisiblePage(PageSlice(1, 5, 10, 1, 1), selected.revision)!!
        assertEquals(1, visible.visiblePage)
        assertEquals(16, controller.extendToPage(PageSlice(2, 10, 16, 2, 2), visible.revision)!!.range.end)
    }

    @Test fun imagesSeparateSentences() {
        val text = "Before\uFFFC之后。"
        assertEquals(TextSelectionRange(0, 6), SentenceBoundaryResolver.sentenceRangeAt(text, 2))
        assertNull(SentenceBoundaryResolver.sentenceRangeAt(text, 6))
        assertEquals(TextSelectionRange(7, 10), SentenceBoundaryResolver.sentenceRangeAt(text, 7))
    }
}
