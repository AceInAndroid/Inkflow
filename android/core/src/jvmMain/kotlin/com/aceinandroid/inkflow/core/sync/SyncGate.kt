package com.aceinandroid.inkflow.core.sync

/** Process-local admission control. Lifecycle callers drain pending work only after saving reading state. */
class SyncGate {
    enum class Activity { ACTIVE_READING, IDLE, BACKGROUND }
    enum class Trigger { AUTOMATIC, MANUAL, LOCAL_MUTATION }
    data class State(val activity: Activity, val running: Boolean, val pendingAutomatic: Boolean)

    private val monitor = Any()
    private var activity = Activity.IDLE
    private var running = false
    private var pending = false
    private var readingGeneration = 0L

    fun state(): State = synchronized(monitor) { State(activity, running, pending) }

    /** Does not execute work: changing reading state must never cause a network request by itself. */
    fun setActivity(value: Activity) = synchronized(monitor) { activity = value }

    fun beginReading(): Long = synchronized(monitor) {
        activity = Activity.ACTIVE_READING
        ++readingGeneration
    }

    /** A late save from an old reader cannot release a newer active reading session. */
    fun finishReading(generation: Long, background: Boolean = false): Boolean = synchronized(monitor) {
        if (generation != readingGeneration) return@synchronized false
        activity = if (background) Activity.BACKGROUND else Activity.IDLE
        true
    }

    fun deferAutomatic() = synchronized(monitor) { pending = true }

    /** Automatic triggers coalesce to one intent. Manual sync bypasses reading deferral. */
    fun tryAcquire(trigger: Trigger): Lease? = synchronized(monitor) {
        if (running || (trigger == Trigger.AUTOMATIC && activity == Activity.ACTIVE_READING)) {
            if (trigger == Trigger.AUTOMATIC) pending = true
            return@synchronized null
        }
        running = true
        if (trigger == Trigger.AUTOMATIC) pending = false
        Lease()
    }

    inner class Lease internal constructor() : AutoCloseable {
        private var closed = false
        override fun close() = synchronized(monitor) {
            if (!closed) {
                closed = true
                running = false
            }
        }
    }
}
