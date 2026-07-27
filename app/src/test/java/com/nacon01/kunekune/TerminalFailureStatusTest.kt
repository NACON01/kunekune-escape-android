package com.nacon01.kunekune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalFailureStatusTest {
    @Test
    fun terminalReasonSurvivesResourceCleanupUntilAcknowledged() {
        val status = TerminalFailureStatus()
        status.record("ARCore failed")

        // Cleanup transitions to IDLE without clearing this status.
        assertEquals("ARCore failed", status.current())
        status.acknowledge()
        assertNull(status.current())
    }
}
