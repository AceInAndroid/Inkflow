package com.aceinandroid.inkflow

import com.aceinandroid.inkflow.core.sync.SyncGate
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class NativeSyncIntegrationTest {
    @Test fun workerDeferralDoesNoWorkAndFailedAutomaticCanDrainAtNextSafeBoundary() {
        val integration = NativeSyncIntegration
        integration.readingStarted()
        assertNull(integration.automatic { fail("Automatic sync ran while reading"); 1 })
        assertTrue(integration.gate.state().pendingAutomatic)
        integration.readingSaved(background = true)
        try {
            integration.automatic<Int> { throw IOException("Offline") }
            fail("Expected offline failure")
        } catch (_: IOException) { }
        assertFalse(integration.gate.state().running)
        assertTrue(integration.gate.state().pendingAutomatic)
        assertEquals(2, integration.automatic { 2 })
        assertFalse(integration.gate.state().pendingAutomatic)
    }

    @Test fun localImportAndSettingsShareExclusionAndAlwaysReleaseAfterFailure() {
        val integration = NativeSyncIntegration
        integration.readingSaved()
        try {
            integration.localMutation {
                assertNull(integration.automatic { fail("Sync overlapped import"); 1 })
                try { integration.manual { fail("Manual sync overlapped import") }; fail("Expected busy") }
                catch (_: IOException) { }
                throw IOException("Import failed")
            }
        } catch (_: IOException) { }
        assertFalse(integration.gate.state().running)
        assertTrue(integration.gate.state().pendingAutomatic)
        assertEquals(3, integration.automatic { 3 })
        assertEquals(SyncGate.Activity.IDLE, integration.gate.state().activity)
    }
}
