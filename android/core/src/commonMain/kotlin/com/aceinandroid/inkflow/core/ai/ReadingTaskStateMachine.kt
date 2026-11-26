package com.aceinandroid.inkflow.core.ai

/** Strings deliberately preserve future task/status identifiers on disk. */
data class ReadingTaskState(val type: String, val status: String, val durable: Boolean = true,
                            val progress: Double = 0.0, val canPause: Boolean = true)
enum class ReadingTaskEvent { START, RESUME, PAUSE, COMPLETE, FAIL, CANCEL }

class ReadingTaskStateMachine {
    companion object {
        val supportedTypes = setOf("native.book.wiki.v1", "native.book.atlas.v1", "native.book.notes.v1", "native.notes.organize.v1")
        val supportedStatuses = setOf("queued", "running", "paused", "completed", "failed", "cancelled")
    }
    fun transition(state: ReadingTaskState, event: ReadingTaskEvent, userAuthorized: Boolean = false): ReadingTaskState {
        require(state.type in supportedTypes && state.status in supportedStatuses) { "Unsupported task" }
        require(state.progress.isFinite() && state.progress in 0.0..1.0)
        val next = when (event) {
            ReadingTaskEvent.START -> { require(userAuthorized && state.status == "queued"); "running" }
            ReadingTaskEvent.RESUME -> { require(userAuthorized && state.status in setOf("paused", "failed")); "running" }
            ReadingTaskEvent.PAUSE -> { require(state.canPause && state.status in setOf("running", "queued")); "paused" }
            ReadingTaskEvent.COMPLETE -> { require(state.status == "running"); "completed" }
            ReadingTaskEvent.FAIL -> { require(state.status == "running"); "failed" }
            ReadingTaskEvent.CANCEL -> { require(state.status in setOf("queued", "running", "paused", "failed")); "cancelled" }
        }
        return state.copy(status = next, progress = if (next == "completed") 1.0 else state.progress)
    }
    /** Restoration is local only and never grants authority to resume a provider call. */
    fun restore(state: ReadingTaskState): ReadingTaskState {
        if (state.type !in supportedTypes || state.status !in supportedStatuses) return state
        return if (state.status in setOf("running", "queued"))
            state.copy(status = if (state.durable) "paused" else "cancelled") else state
    }
}
