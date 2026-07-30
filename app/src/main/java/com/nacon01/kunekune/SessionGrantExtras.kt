package com.nacon01.kunekune

import android.content.Intent

/** Stable serialization for the grant carried from the marker workflow to the service. */
object SessionGrantExtras {
    fun putInto(intent: Intent, grant: SessionGrant) {
        intent.putExtra(BackgroundTrackingService.EXTRA_ROUTE_ID, grant.routeId)
        intent.putStringArrayListExtra(
            BackgroundTrackingService.EXTRA_GRANTED_TARGET_IDS,
            ArrayList(grant.grantedTargetIds.sorted())
        )
        intent.putExtra(BackgroundTrackingService.EXTRA_INITIAL_TARGET_ID, grant.initialTargetId)
        intent.putExtra(BackgroundTrackingService.EXTRA_VISIT_GENERATION, grant.visitGeneration)
    }

    fun hasAny(intent: Intent): Boolean = listOf(
        BackgroundTrackingService.EXTRA_ROUTE_ID,
        BackgroundTrackingService.EXTRA_GRANTED_TARGET_IDS,
        BackgroundTrackingService.EXTRA_INITIAL_TARGET_ID,
        BackgroundTrackingService.EXTRA_VISIT_GENERATION
    ).any(intent::hasExtra)

    fun readFrom(intent: Intent): SessionGrant? {
        if (!hasAny(intent)) return null
        val routeId = intent.getStringExtra(BackgroundTrackingService.EXTRA_ROUTE_ID)
            ?: throw IllegalArgumentException("Route ID is missing")
        val targetIds = intent.getStringArrayListExtra(
            BackgroundTrackingService.EXTRA_GRANTED_TARGET_IDS
        )?.toSet() ?: throw IllegalArgumentException("Granted target IDs are missing")
        val initialTargetId = intent.getStringExtra(BackgroundTrackingService.EXTRA_INITIAL_TARGET_ID)
            ?: throw IllegalArgumentException("Initial target ID is missing")
        check(intent.hasExtra(BackgroundTrackingService.EXTRA_VISIT_GENERATION)) {
            "Visit generation is missing"
        }
        return SessionGrant(
            visitGeneration = intent.getLongExtra(
                BackgroundTrackingService.EXTRA_VISIT_GENERATION,
                -1L
            ),
            routeId = routeId,
            grantedTargetIds = targetIds,
            initialTargetId = initialTargetId
        )
    }
}
