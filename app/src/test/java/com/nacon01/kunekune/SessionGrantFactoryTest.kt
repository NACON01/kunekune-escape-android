package com.nacon01.kunekune

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionGrantFactoryTest {
    @Test
    fun grantContainsOnlyTheSelectedSubsetAndTheInitialTarget() {
        val grant = SessionGrantFactory.create(
            visitGeneration = 0L,
            routeId = "123e4567-e89b-12d3-a456-426614174000",
            configuredSelectedTargetIds = setOf("app:a", "domain:example.com"),
            grantedTargetIds = setOf("domain:example.com"),
            initialTargetId = "domain:example.com"
        )

        assertEquals(setOf("domain:example.com"), grant.grantedTargetIds)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unselectedConfiguredTargetCannotBeGranted() {
        SessionGrantFactory.create(
            0L,
            "123e4567-e89b-12d3-a456-426614174000",
            setOf("app:a"),
            setOf("app:a", "app:b"),
            "app:a"
        )
    }
}
