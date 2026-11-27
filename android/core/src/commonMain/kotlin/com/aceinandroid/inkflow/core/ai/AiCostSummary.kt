package com.aceinandroid.inkflow.core.ai

/** Token accounting only. Reported usage is transport-reported, not a currency invoice. */
enum class AiCostUsageKind { REPORTED, ESTIMATED, MIXED_OR_UNKNOWN }
enum class AiCostDimension { BOOK, TASK, REQUEST, PROVIDER, ROLE, WORKLOAD, MODEL, DAY }

/** Only observed identifiers belong here; null means attribution was not recorded. */
data class AiCostAttribution(
    val bookId: Long? = null,
    val taskId: String? = null,
    val requestId: String? = null,
    val providerId: String? = null,
    /** The observed workload routing role, not a guess from current provider assignments. */
    val role: AiProviderRole? = null,
    val workload: String? = null,
    val model: String? = null,
    val day: String? = null,
) {
    init {
        require(listOf(taskId, requestId, providerId, workload, model, day).all { it == null || it.isNotBlank() }) {
            "Cost attribution identifiers must be nonblank or unknown"
        }
    }

    internal fun value(dimension: AiCostDimension): String? = when (dimension) {
        AiCostDimension.BOOK -> bookId?.toString()
        AiCostDimension.TASK -> taskId
        AiCostDimension.REQUEST -> requestId
        AiCostDimension.PROVIDER -> providerId
        AiCostDimension.ROLE -> role?.name
        AiCostDimension.WORKLOAD -> workload
        AiCostDimension.MODEL -> model
        AiCostDimension.DAY -> day
    }
}

data class AiCostTokens(val input: Long = 0, val output: Long = 0) {
    init {
        require(input >= 0 && output >= 0) { "Negative token count" }
        costAdd(input, output)
    }
    val total: Long get() = costAdd(input, output)
    operator fun plus(other: AiCostTokens): AiCostTokens =
        AiCostTokens(costAdd(input, other.input), costAdd(output, other.output))
}

/** A source row may aggregate many requests. Null requestCount must never become one. */
data class AiCostRecord(
    val tokens: AiCostTokens,
    val usageKind: AiCostUsageKind,
    val attribution: AiCostAttribution = AiCostAttribution(),
    val requestCount: Long? = null,
) {
    init { require(requestCount == null || requestCount >= 0) { "Negative request count" } }

    companion object {
        /** Use only for an individual usage result, never for a legacy OR-merged estimated flag. */
        fun fromUsage(usage: AiTokenUsage, attribution: AiCostAttribution = AiCostAttribution(),
                      requestCount: Long? = null): AiCostRecord = AiCostRecord(
            AiCostTokens(usage.input, usage.output),
            if (usage.estimated) AiCostUsageKind.ESTIMATED else AiCostUsageKind.REPORTED,
            attribution, requestCount,
        )
    }
}

data class AiCostTotals(
    val reported: AiCostTokens,
    val estimated: AiCostTokens,
    val mixedOrUnknown: AiCostTokens,
    val sourceRecordCount: Long,
    val knownRequestCount: Long,
    val recordsWithoutRequestCount: Long,
) {
    /** Exact request count is unavailable if even one source row lacks it. */
    val requestCount: Long? get() = knownRequestCount.takeIf { recordsWithoutRequestCount == 0L }
    val tokens: AiCostTokens get() = reported + estimated + mixedOrUnknown
}

/** A null key value is a real unknown bucket, distinct from any literal identifier. */
data class AiCostGroup(val key: Map<AiCostDimension, String?>, val totals: AiCostTotals)
data class AiCostSummary(val totals: AiCostTotals, val groups: List<AiCostGroup>)

object AiCostSummarizer {
    /** Group only by recorded fields. No model/provider lookup, task/time join or role inference. */
    fun summarize(records: List<AiCostRecord>, dimensions: Set<AiCostDimension> = emptySet()): AiCostSummary {
        val orderedDimensions = AiCostDimension.entries.filter { it in dimensions }
        val total = Accumulator()
        val groups = linkedMapOf<Map<AiCostDimension, String?>, Accumulator>()
        records.forEach { record ->
            total.add(record)
            val key = orderedDimensions.associateWith { record.attribution.value(it) }
            groups.getOrPut(key) { Accumulator() }.add(record)
        }
        return AiCostSummary(total.snapshot(), groups.map { (key, value) -> AiCostGroup(key, value.snapshot()) })
    }

    private class Accumulator {
        private var reported = AiCostTokens()
        private var estimated = AiCostTokens()
        private var unresolved = AiCostTokens()
        private var rows = 0L
        private var knownRequests = 0L
        private var unknownRequests = 0L

        fun add(record: AiCostRecord) {
            when (record.usageKind) {
                AiCostUsageKind.REPORTED -> reported += record.tokens
                AiCostUsageKind.ESTIMATED -> estimated += record.tokens
                AiCostUsageKind.MIXED_OR_UNKNOWN -> unresolved += record.tokens
            }
            rows = costAdd(rows, 1)
            val requests = record.requestCount
            if (requests == null) unknownRequests = costAdd(unknownRequests, 1)
            else knownRequests = costAdd(knownRequests, requests)
        }

        fun snapshot(): AiCostTotals = AiCostTotals(reported, estimated, unresolved, rows,
            knownRequests, unknownRequests).also { it.tokens } // Validate across all quality buckets too.
    }
}

private fun costAdd(left: Long, right: Long): Long {
    require(left >= 0 && right >= 0 && left <= Long.MAX_VALUE - right) { "Cost counter overflow" }
    return left + right
}
