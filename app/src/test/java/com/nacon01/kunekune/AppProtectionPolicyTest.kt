package com.nacon01.kunekune

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class AppProtectionPolicyTest {
    private val routeId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000").toString()
    private val app = BlockTarget.app("com.example.blocked", "Blocked")
    private val otherApp = BlockTarget.app("com.example.other", "Other")
    private val ownApp = BlockTarget.app("com.nacon01.kunekune", "Kunekune Escape")
    private val domain = BlockTarget.domain("example.com", false)

    @Test
    fun outsideAndUnknownWithoutHistoryAreOff() {
        assertEquals(
            AppProtectionDecision.OutsideOrOff,
            decide(snapshot(lastKnownInside = false), app, "com.example.blocked")
        )
        assertEquals(
            AppProtectionDecision.OutsideOrOff,
            decide(snapshot(lastKnownInside = null), app, "com.example.blocked")
        )
    }

    @Test
    fun unknownAfterInsidePreservesProtectionDecision() {
        val result = decide(
            snapshot(lastKnownInside = true, unknownWarning = true),
            app,
            "com.example.blocked"
        )
        assertEquals(AppProtectionDecision.Block(app.id, app.packageName, app.label), result)
    }

    @Test
    fun onlySelectedAppsBlockAndDomainsNeverBlock() {
        assertEquals(
            AppProtectionDecision.Block(app.id, app.packageName, app.label),
            decide(snapshot(), app, app.packageName)
        )
        assertEquals(
            AppProtectionDecision.ForegroundNotBlocked,
            decide(snapshot(), otherApp, app.packageName)
        )
        assertEquals(
            AppProtectionDecision.NoSelectedAppTarget,
            decide(snapshot(), domain, "com.example.blocked")
        )
        assertEquals(
            AppProtectionDecision.Block(app.id, app.packageName, app.label),
            decide(snapshot(), listOf(app, domain), app.packageName)
        )
    }

    @Test
    fun currentGenerationGrantUnlocksAndStaleGrantDoesNot() {
        val granted = snapshot(
            state = HomeZoneState.GUIDANCE_ACTIVE,
            grantedTargetIds = setOf(app.id)
        )
        assertEquals(
            AppProtectionDecision.ForegroundNotBlocked,
            decide(granted, app, app.packageName)
        )
        val stale = snapshot(
            state = HomeZoneState.GUIDANCE_ACTIVE,
            generation = 2L,
            grantedTargetIds = setOf(app.id),
            grantGeneration = 1L
        )
        assertEquals(
            AppProtectionDecision.Block(app.id, app.packageName, app.label),
            decide(stale, app, app.packageName)
        )
    }

    @Test
    fun grantRequiresInsideAndForegroundTargetMembership() {
        val outside = snapshot(
            state = HomeZoneState.GUIDANCE_ACTIVE,
            lastKnownInside = false,
            grantedTargetIds = setOf(app.id)
        )
        assertEquals(
            AppProtectionDecision.OutsideOrOff,
            decide(outside, app, app.packageName)
        )

        val differentTargetGranted = snapshot(
            state = HomeZoneState.GUIDANCE_ACTIVE,
            grantedTargetIds = setOf(otherApp.id)
        )
        assertEquals(
            AppProtectionDecision.Block(app.id, app.packageName, app.label),
            decide(differentTargetGranted, app, app.packageName)
        )
    }

    @Test
    fun currentGrantRemainsValidDuringGuidanceStates() {
        listOf(HomeZoneState.STARTING_GUIDANCE, HomeZoneState.GUIDANCE_ACTIVE).forEach { state ->
            val snapshot = snapshot(grantedTargetIds = setOf(app.id)).copy(state = state)
            assertEquals(
                AppProtectionDecision.ForegroundNotBlocked,
                decide(snapshot, app, app.packageName)
            )
        }
    }

    @Test
    fun sameGenerationGrantDoesNotUnlockOutsideGuidanceStates() {
        listOf(
            HomeZoneState.INSIDE_LOCKED,
            HomeZoneState.OUTSIDE_OFF,
            HomeZoneState.AWAITING_MARKER,
            HomeZoneState.AWAITING_DESTINATION,
            HomeZoneState.AWAITING_TARGET_SELECTION
        ).forEach { state ->
            val snapshot = snapshot(
                state = state,
                grantedTargetIds = setOf(app.id)
            )
            assertEquals(
                AppProtectionDecision.Block(app.id, app.packageName, app.label),
                decide(snapshot, app, app.packageName)
            )
        }
    }

    @Test
    fun ownPackageIsNeverBlocked() {
        assertEquals(
            AppProtectionDecision.ForegroundNotBlocked,
            decide(snapshot(), listOf(app, ownApp), ownApp.packageName)
        )
    }

    @Test
    fun statusHelperUsesSafePrecedence() {
        assertEquals(
            AppProtectionStatus.OUTSIDE_OFF,
            AppProtectionStatusPolicy.resolve(snapshot(lastKnownInside = null), 1, false, false)
        )
        assertEquals(
            AppProtectionStatus.NO_SELECTED_APP_TARGET,
            AppProtectionStatusPolicy.resolve(snapshot(), 0, false, false)
        )
        assertEquals(
            AppProtectionStatus.USAGE_ACCESS_MISSING,
            AppProtectionStatusPolicy.resolve(snapshot(), 1, false, true)
        )
        assertEquals(
            AppProtectionStatus.OVERLAY_PERMISSION_MISSING,
            AppProtectionStatusPolicy.resolve(snapshot(), 1, true, false)
        )
    }

    private fun decide(
        snapshot: HomeZoneSnapshot,
        targets: Iterable<BlockTarget>,
        foreground: String
    ): AppProtectionDecision = AppProtectionPolicy.decide(
        snapshot = snapshot,
        selectedTargets = targets,
        foregroundPackage = foreground,
        ownPackage = "com.nacon01.kunekune"
    )

    private fun decide(
        snapshot: HomeZoneSnapshot,
        target: BlockTarget,
        foreground: String
    ): AppProtectionDecision = decide(snapshot, listOf(target), foreground)

    private fun snapshot(
        state: HomeZoneState = HomeZoneState.INSIDE_LOCKED,
        lastKnownInside: Boolean? = true,
        unknownWarning: Boolean = false,
        generation: Long = 0L,
        grantedTargetIds: Set<String> = emptySet(),
        grantGeneration: Long = generation
    ): HomeZoneSnapshot = HomeZoneSnapshot(
        state = state,
        visitGeneration = generation,
        sessionGrant = if (grantedTargetIds.isEmpty()) null else SessionGrant(
            visitGeneration = grantGeneration,
            routeId = routeId,
            grantedTargetIds = grantedTargetIds,
            initialTargetId = grantedTargetIds.first()
        ),
        lastKnownInside = lastKnownInside,
        unknownWarning = unknownWarning,
        stopAll = false
    )
}
