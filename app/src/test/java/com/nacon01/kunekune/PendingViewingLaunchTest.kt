package com.nacon01.kunekune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingViewingLaunchTest {
    @Test
    fun waitsUntilServiceCameraIsReadyAndCompletesAfterSuccessfulLaunch() {
        val pending = PendingViewingLaunch()
        pending.prepare(ViewingTarget.BROWSER)

        assertTrue(pending.isPending())
        assertNull(pending.pendingTargetIfReady())

        pending.markReady()
        assertEquals(ViewingTarget.BROWSER, pending.pendingTargetIfReady())
        assertEquals(ViewingTarget.BROWSER, pending.pendingTargetIfReady())
        assertTrue(pending.complete(ViewingTarget.BROWSER))
        assertFalse(pending.isPending())
        assertNull(pending.pendingTargetIfReady())
        assertFalse(pending.complete(ViewingTarget.BROWSER))
    }

    @Test
    fun failedOrMismatchedLaunchKeepsTargetForRetry() {
        val pending = PendingViewingLaunch()
        pending.prepare(ViewingTarget.BROWSER)
        pending.markReady()

        assertFalse(pending.complete(ViewingTarget.YOUTUBE_APP))
        assertEquals(ViewingTarget.BROWSER, pending.pendingTargetIfReady())
    }

    @Test
    fun newSessionReplacesStaleTargetAndRequiresReadinessAgain() {
        val pending = PendingViewingLaunch()
        pending.prepare(ViewingTarget.YOUTUBE_APP)
        pending.markReady()
        pending.prepare(ViewingTarget.BROWSER)

        assertNull(pending.pendingTargetIfReady())
        pending.markReady()
        assertEquals(ViewingTarget.BROWSER, pending.pendingTargetIfReady())
    }
}
