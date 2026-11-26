package com.aceinandroid.inkflow

import com.aceinandroid.inkflow.core.sync.SyncGate
import java.io.IOException

/** Shared by WebDAV, progress worker/settings hooks, and wireless imports. No timers or network side effects. */
internal object NativeSyncIntegration {
    val gate = SyncGate()

    fun readingStarted(): Long = gate.beginReading()

    /** Call AFTER the reader has persisted position, runtime and session duration. */
    fun readingSaved(background: Boolean = false, generation: Long? = null) {
        if (generation != null) gate.finishReading(generation, background)
        else gate.setActivity(if (background) SyncGate.Activity.BACKGROUND else SyncGate.Activity.IDLE)
    }

    fun <T> manual(block: () -> T): T = exclusive(SyncGate.Trigger.MANUAL, block)
    fun <T> localMutation(block: () -> T): T = exclusive(SyncGate.Trigger.LOCAL_MUTATION, block)

    /** Null means deferred; a worker should finish successfully and let the next safe lifecycle edge drain it. */
    fun <T> automatic(eligible: Boolean = true, block: () -> T): T? {
        if (!eligible) {
            gate.deferAutomatic()
            return null
        }
        val lease = gate.tryAcquire(SyncGate.Trigger.AUTOMATIC) ?: return null
        return lease.use {
            try { block() } catch (failure: Exception) {
                gate.deferAutomatic()
                throw failure
            }
        }
    }

    /** Replacement for the foreground lifecycle call; retains the existing progress-sync cooldown. */
    fun automaticProgress(sync: NativeProgressSync, store: BookStore): Int? {
        if (!sync.automaticEnabled() || !sync.account().signedIn) return null
        return sync.synchronizeAutomatically(store)
    }

    /** Replacement for the settings "sync now" call. WebDAV entry points already acquire the gate. */
    fun manualProgress(sync: NativeProgressSync, store: BookStore): Int = manual { sync.synchronize(store) }

    /** True requests a worker retry for unsent positions; a deferred reader/busy edge completes quietly. */
    fun progressWorkerNeedsRetry(sync: NativeProgressSync, store: BookStore): Boolean {
        if (!sync.automaticEnabled() || !sync.account().signedIn) return false
        return sync.synchronizeAutomatically(store) != null && sync.hasPendingPositions(store)
    }

    private fun <T> exclusive(trigger: SyncGate.Trigger, block: () -> T): T {
        val lease = gate.tryAcquire(trigger) ?: throw IOException("Another sync or library import is running")
        return lease.use { block() }
    }
}
