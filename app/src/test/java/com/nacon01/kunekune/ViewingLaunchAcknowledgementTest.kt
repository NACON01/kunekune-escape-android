package com.nacon01.kunekune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewingLaunchAcknowledgementTest {
    @Test
    fun acknowledgementRequiresThePendingTargetAndActualPackage() {
        val pending = PendingViewingLaunch()
        pending.prepare(ViewingTarget.BROWSER)
        pending.markReady()

        assertFalse(pending.complete(ViewingTarget.BROWSER, ""))
        assertFalse(pending.complete(ViewingTarget.YOUTUBE_APP, "com.example.browser"))
        assertTrue(pending.complete(ViewingTarget.BROWSER, "com.example.browser"))
        assertEquals(null, pending.pendingTargetIfReady())
    }
}
