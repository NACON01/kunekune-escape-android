package com.nacon01.kunekune

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureInPicturePermissionTest {
    @Test
    fun unshownPackageRequiresInitialSetup() {
        assertTrue(
            PictureInPicturePermission.shouldOpenInitialSetup(
                "com.google.android.youtube",
                guidanceShown = false
            )
        )
    }

    @Test
    fun shownPackageStartsWithoutInitialSetup() {
        assertFalse(
            PictureInPicturePermission.shouldOpenInitialSetup(
                "com.android.chrome",
                guidanceShown = true
            )
        )
    }

    @Test
    fun blankPackageDoesNotOpenSetup() {
        assertFalse(PictureInPicturePermission.shouldOpenInitialSetup("", guidanceShown = false))
    }
}
