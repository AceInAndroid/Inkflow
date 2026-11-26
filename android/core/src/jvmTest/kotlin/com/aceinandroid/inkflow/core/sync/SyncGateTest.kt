package com.aceinandroid.inkflow.core.sync

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SyncGateTest {
    @org.junit.Test fun latePositionSaveCannotReleaseNewReadingSession() {
        val gate = SyncGate()
        val first = gate.beginReading()
        val second = gate.beginReading()
        org.junit.Assert.assertFalse(gate.finishReading(first))
        org.junit.Assert.assertNull(gate.tryAcquire(SyncGate.Trigger.AUTOMATIC))
        org.junit.Assert.assertTrue(gate.finishReading(second, background = true))
        gate.tryAcquire(SyncGate.Trigger.AUTOMATIC)!!.close()
    }

    @Test fun readingCoalescesAndManualBypassesDeferral() {
        val gate = SyncGate()
        gate.setActivity(SyncGate.Activity.ACTIVE_READING)
        repeat(20) { assertNull(gate.tryAcquire(SyncGate.Trigger.AUTOMATIC)) }
        assertTrue(gate.state().pendingAutomatic)
        gate.tryAcquire(SyncGate.Trigger.MANUAL)!!.use {
            assertTrue(gate.state().pendingAutomatic)
            assertNull(gate.tryAcquire(SyncGate.Trigger.MANUAL))
            assertNull(gate.tryAcquire(SyncGate.Trigger.AUTOMATIC))
        }
        gate.setActivity(SyncGate.Activity.BACKGROUND)
        assertTrue(gate.state().pendingAutomatic)
        assertFalse(gate.state().running)
        gate.tryAcquire(SyncGate.Trigger.AUTOMATIC)!!.use {
            assertFalse(gate.state().pendingAutomatic)
        }
    }

    @Test fun closingOldLeaseTwiceCannotReleaseNewFlight() {
        val gate = SyncGate()
        val old = gate.tryAcquire(SyncGate.Trigger.MANUAL)!!
        old.close()
        gate.tryAcquire(SyncGate.Trigger.LOCAL_MUTATION)!!.use {
            old.close()
            assertNull(gate.tryAcquire(SyncGate.Trigger.MANUAL))
            assertNull(gate.tryAcquire(SyncGate.Trigger.AUTOMATIC))
        }
        assertTrue(gate.state().pendingAutomatic)
    }

    @Test fun simultaneousCallersAdmitOneAndPreservePendingIntent() {
        val gate = SyncGate()
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val acquired = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val winner = executor.submit {
                gate.tryAcquire(SyncGate.Trigger.MANUAL)!!.use {
                    acquired.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(acquired.await(5, TimeUnit.SECONDS))
            val requests = (0 until 30).map { executor.submit<Boolean> {
                start.await()
                gate.tryAcquire(SyncGate.Trigger.AUTOMATIC) == null
            } }
            start.countDown()
            requests.forEach { assertTrue(it.get(5, TimeUnit.SECONDS)) }
            release.countDown()
            winner.get(5, TimeUnit.SECONDS)
            assertTrue(gate.state().pendingAutomatic)
            gate.tryAcquire(SyncGate.Trigger.AUTOMATIC)!!.close()
            assertFalse(gate.state().pendingAutomatic)
        } finally { release.countDown(); executor.shutdownNow() }
    }
}
