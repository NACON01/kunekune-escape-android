package com.nacon01.kunekune

/** The only targets that can be enforced by the selected-app protection phase. */
data class SelectedAppProtectionTarget(
    val targetId: String,
    val packageName: String,
    val label: String
)

sealed interface AppProtectionDecision {
    data object OutsideOrOff : AppProtectionDecision
    data object NoSelectedAppTarget : AppProtectionDecision
    data object NoForegroundPackage : AppProtectionDecision
    data object ForegroundNotBlocked : AppProtectionDecision
    data class Block(
        val targetId: String,
        val packageName: String,
        val label: String
    ) : AppProtectionDecision
}

/** Pure policy for deciding whether a foreground package is in the app block scope. */
object AppProtectionPolicy {
    fun decide(
        snapshot: HomeZoneSnapshot,
        selectedTargets: Iterable<BlockTarget>,
        foregroundPackage: String?,
        ownPackage: String
    ): AppProtectionDecision {
        if (snapshot.lastKnownInside != true) return AppProtectionDecision.OutsideOrOff

        val selectedApps = selectedTargets.mapNotNull { target ->
            (target as? BlockTarget.App)?.let {
                SelectedAppProtectionTarget(it.id, it.packageName, it.label)
            }
        }
        if (selectedApps.isEmpty()) return AppProtectionDecision.NoSelectedAppTarget

        val foreground = foregroundPackage?.takeIf { it.isNotBlank() }
            ?: return AppProtectionDecision.NoForegroundPackage
        if (foreground == ownPackage) return AppProtectionDecision.ForegroundNotBlocked

        val grant = snapshot.sessionGrant
            ?.takeIf {
                snapshot.state == HomeZoneState.STARTING_GUIDANCE ||
                    snapshot.state == HomeZoneState.GUIDANCE_ACTIVE
            }
            ?.takeIf { it.visitGeneration == snapshot.visitGeneration }
        val grantedIds = grant?.grantedTargetIds.orEmpty()
        val blocked = selectedApps.firstOrNull {
            it.packageName == foreground && it.targetId !in grantedIds
        } ?: return AppProtectionDecision.ForegroundNotBlocked
        return AppProtectionDecision.Block(blocked.targetId, blocked.packageName, blocked.label)
    }
}

enum class AppProtectionStatus {
    ACTIVE,
    OUTSIDE_OFF,
    NO_SELECTED_APP_TARGET,
    USAGE_ACCESS_MISSING,
    OVERLAY_PERMISSION_MISSING,
    ERROR
}

object AppProtectionStatusPolicy {
    fun resolve(
        snapshot: HomeZoneSnapshot,
        selectedAppTargetCount: Int,
        usageAccessGranted: Boolean,
        overlayPermissionGranted: Boolean,
        error: Boolean = false
    ): AppProtectionStatus = when {
        snapshot.lastKnownInside != true -> AppProtectionStatus.OUTSIDE_OFF
        selectedAppTargetCount == 0 -> AppProtectionStatus.NO_SELECTED_APP_TARGET
        error -> AppProtectionStatus.ERROR
        !usageAccessGranted -> AppProtectionStatus.USAGE_ACCESS_MISSING
        !overlayPermissionGranted -> AppProtectionStatus.OVERLAY_PERMISSION_MISSING
        else -> AppProtectionStatus.ACTIVE
    }
}
