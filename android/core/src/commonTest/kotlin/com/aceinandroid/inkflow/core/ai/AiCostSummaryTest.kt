package com.aceinandroid.inkflow.core.ai

import kotlin.test.*

class AiCostSummaryTest {
    private val full = AiCostAttribution(
        bookId = 42, taskId = "task.1", requestId = "request.1", providerId = "provider.1",
        role = AiProviderRole.LIGHTWEIGHT, workload = "reading.translate", model = "model.1", day = "2026-11-27",
    )
    private fun record(input: Long = 10, output: Long = 5,
                       kind: AiCostUsageKind = AiCostUsageKind.REPORTED,
                       attribution: AiCostAttribution = full, count: Long? = null) =
        AiCostRecord(AiCostTokens(input, output), kind, attribution, count)

    @Test fun individualTransportUsageKeepsReportedAndEstimatedSeparate() {
        val summary = AiCostSummarizer.summarize(listOf(
            AiCostRecord.fromUsage(AiTokenUsage(12, 4), full, requestCount = 1),
            AiCostRecord.fromUsage(AiTokenTracker.estimate("abcd", "abcdefgh"), full, requestCount = 1),
        ))
        assertEquals(AiCostTokens(12, 4), summary.totals.reported)
        assertEquals(AiCostTokens(1, 2), summary.totals.estimated)
        assertEquals(AiCostTokens(), summary.totals.mixedOrUnknown)
        assertEquals(2L, summary.totals.requestCount)
    }

    @Test fun legacyMixedTotalsNeverBecomeReportedOrPureEstimatedTokens() {
        val totals = AiCostSummarizer.summarize(listOf(
            record(10, 1), record(20, 2, AiCostUsageKind.ESTIMATED),
            record(30, 3, AiCostUsageKind.MIXED_OR_UNKNOWN),
        )).totals
        assertEquals(AiCostTokens(10, 1), totals.reported)
        assertEquals(AiCostTokens(20, 2), totals.estimated)
        assertEquals(AiCostTokens(30, 3), totals.mixedOrUnknown)
        assertEquals(66L, totals.tokens.total)
    }

    @Test fun everyDimensionPreservesItsUnknownBucketAndConservesTotals() {
        val expected = mapOf(
            AiCostDimension.BOOK to "42", AiCostDimension.TASK to "task.1",
            AiCostDimension.REQUEST to "request.1", AiCostDimension.PROVIDER to "provider.1",
            AiCostDimension.ROLE to "LIGHTWEIGHT", AiCostDimension.WORKLOAD to "reading.translate",
            AiCostDimension.MODEL to "model.1", AiCostDimension.DAY to "2026-11-27",
        )
        for ((dimension, value) in expected) {
            val summary = AiCostSummarizer.summarize(listOf(record(),
                record(7, 3, attribution = AiCostAttribution())), setOf(dimension))
            assertEquals(2, summary.groups.size, dimension.name)
            assertEquals(15L, summary.groups.single { it.key[dimension] == value }.totals.tokens.total)
            assertEquals(10L, summary.groups.single { it.key[dimension] == null }.totals.tokens.total)
            assertEquals(summary.totals.tokens.total, summary.groups.sumOf { it.totals.tokens.total })
            assertTrue(summary.groups.all { it.key.keys == setOf(dimension) })
        }
    }

    @Test fun sameModelDoesNotMergeDifferentProvidersBooksTasksOrRequests() {
        val another = full.copy(bookId = 99, taskId = "task.2", requestId = "request.2", providerId = "provider.2")
        val dimensions = setOf(AiCostDimension.MODEL, AiCostDimension.PROVIDER,
            AiCostDimension.BOOK, AiCostDimension.TASK, AiCostDimension.REQUEST)
        val summary = AiCostSummarizer.summarize(listOf(record(), record(attribution = another)), dimensions)
        assertEquals(2, summary.groups.size)
        assertEquals(setOf("model.1"), summary.groups.map { it.key[AiCostDimension.MODEL] }.toSet())
        assertEquals(setOf("provider.1", "provider.2"), summary.groups.map { it.key[AiCostDimension.PROVIDER] }.toSet())
    }

    @Test fun knownRecordCannotFillAnotherRecordsMissingLinks() {
        val unknown = full.copy(bookId = null, taskId = null, requestId = null, providerId = null, role = null, workload = null)
        val summary = AiCostSummarizer.summarize(listOf(record(), record(attribution = unknown)),
            setOf(AiCostDimension.PROVIDER, AiCostDimension.ROLE, AiCostDimension.BOOK))
        val missing = summary.groups.single { it.key.values.all { value -> value == null } }
        assertEquals(15L, missing.totals.tokens.total)
        assertEquals(2, summary.groups.size)
    }

    @Test fun literalUnknownIdentifierAndAbsentIdentifierRemainDistinct() {
        val summary = AiCostSummarizer.summarize(listOf(
            record(attribution = AiCostAttribution(providerId = "unknown")),
            record(attribution = AiCostAttribution()),
        ), setOf(AiCostDimension.PROVIDER))
        assertEquals(setOf("unknown", null), summary.groups.map { it.key[AiCostDimension.PROVIDER] }.toSet())
    }

    @Test fun aggregationDoesNotInventRequestCountsForDailyRows() {
        val totals = AiCostSummarizer.summarize(listOf(record(count = 2), record(), record())).totals
        assertEquals(3L, totals.sourceRecordCount)
        assertEquals(2L, totals.knownRequestCount)
        assertEquals(2L, totals.recordsWithoutRequestCount)
        assertNull(totals.requestCount)
        assertNull(AiCostRecord.fromUsage(AiTokenUsage(1, 2)).requestCount)
    }

    @Test fun explicitAggregateCountsAreSummedIndependentlyOfSourceRowCounts() {
        val totals = AiCostSummarizer.summarize(listOf(record(count = 8), record(count = 3))).totals
        assertEquals(2L, totals.sourceRecordCount)
        assertEquals(11L, totals.requestCount)
        assertEquals(0L, totals.recordsWithoutRequestCount)
    }

    @Test fun emptyInputHasZeroTotalsAndNoFabricatedGroup() {
        val summary = AiCostSummarizer.summarize(emptyList(), AiCostDimension.entries.toSet())
        assertEquals(AiCostTokens(), summary.totals.tokens)
        assertEquals(0L, summary.totals.sourceRecordCount)
        assertEquals(0L, summary.totals.requestCount)
        assertTrue(summary.groups.isEmpty())
    }

    @Test fun noDimensionsProducesOneCombinedGroupWithAllQualitiesIntact() {
        val summary = AiCostSummarizer.summarize(listOf(record(), record(kind = AiCostUsageKind.ESTIMATED)))
        assertEquals(1, summary.groups.size)
        assertTrue(summary.groups.single().key.isEmpty())
        assertEquals(summary.totals, summary.groups.single().totals)
    }

    @Test fun negativeCountersAndBlankIdentifiersAreRejected() {
        assertFailsWith<IllegalArgumentException> { AiCostTokens(-1, 0) }
        assertFailsWith<IllegalArgumentException> { AiCostTokens(0, -1) }
        assertFailsWith<IllegalArgumentException> { record(count = -1) }
        assertFailsWith<IllegalArgumentException> { full.copy(providerId = "  ") }
    }

    @Test fun inputPlusOutputOverflowIsRejected() {
        assertFailsWith<IllegalArgumentException> { AiCostTokens(Long.MAX_VALUE, 1) }
        assertFailsWith<IllegalArgumentException> { AiCostRecord.fromUsage(AiTokenUsage(Long.MAX_VALUE, 1)) }
        assertEquals(Long.MAX_VALUE, AiCostTokens(Long.MAX_VALUE - 1, 1).total)
    }

    @Test fun overflowWithinOneQualityBucketIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            AiCostSummarizer.summarize(listOf(record(Long.MAX_VALUE, 0), record(1, 0)))
        }
    }

    @Test fun overflowAcrossQualityBucketsIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            AiCostSummarizer.summarize(listOf(record(Long.MAX_VALUE, 0), record(0, 1, AiCostUsageKind.ESTIMATED)))
        }
    }

    @Test fun requestCountOverflowIsRejectedWithoutWrapping() {
        assertFailsWith<IllegalArgumentException> {
            AiCostSummarizer.summarize(listOf(record(count = Long.MAX_VALUE), record(count = 1)))
        }
    }
}
