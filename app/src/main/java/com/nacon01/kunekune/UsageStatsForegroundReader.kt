package com.nacon01.kunekune

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

data class ForegroundPackageResult(
    val packageName: String?,
    val accessGranted: Boolean,
    val hasUsableData: Boolean,
    val differentPackageSincePreviousObservation: Boolean = false,
    val screenNonInteractiveSincePreviousObservation: Boolean = false,
    val reconciliationPending: Boolean = false
)

class UsageStatsForegroundReader(private val context: Context) {
    private val usageStats = context.getSystemService(UsageStatsManager::class.java)
    private val reducer = UsageForegroundReducer()
    private val queryWindow = UsageEventQueryWindow(INITIAL_LOOKBACK_MILLIS)

    fun read(nowMillis: Long = System.currentTimeMillis()): ForegroundPackageResult {
        if (!hasUsageAccess(context)) {
            resetState()
            return ForegroundPackageResult(null, accessGranted = false, hasUsableData = false)
        }
        val window = queryWindow.next(nowMillis)
        if (window == null) {
            val observation = reducer.consumeObservation()
            return ForegroundPackageResult(
                observation.packageName,
                accessGranted = true,
                hasUsableData = observation.packageName != null,
                differentPackageSincePreviousObservation =
                    observation.differentPackageSincePreviousObservation,
                screenNonInteractiveSincePreviousObservation =
                    observation.screenNonInteractiveSincePreviousObservation,
                reconciliationPending = observation.reconciliationPending
            )
        }
        return try {
            if (window.clockRolledBack) reducer.reset()
            val events = usageStats?.queryEvents(
                window.beginMillis,
                window.endMillis
            ) ?: run {
                resetState()
                return ForegroundPackageResult(null, true, false)
            }
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                event.toForegroundUsageEvent()?.let(reducer::apply)
            }
            queryWindow.commit(window.endMillis)
            val observation = reducer.consumeObservation()
            ForegroundPackageResult(
                observation.packageName,
                accessGranted = true,
                hasUsableData = observation.packageName != null,
                differentPackageSincePreviousObservation =
                    observation.differentPackageSincePreviousObservation,
                screenNonInteractiveSincePreviousObservation =
                    observation.screenNonInteractiveSincePreviousObservation,
                reconciliationPending = observation.reconciliationPending
            )
        } catch (_: SecurityException) {
            resetState()
            ForegroundPackageResult(null, accessGranted = false, hasUsableData = false)
        } catch (_: RuntimeException) {
            resetState()
            ForegroundPackageResult(null, accessGranted = true, hasUsableData = false)
        }
    }

    private fun resetState() {
        reducer.reset()
        queryWindow.reset()
    }

    private fun UsageEvents.Event.toForegroundUsageEvent(): ForegroundUsageEvent? {
        val type = when (eventType) {
            UsageEvents.Event.ACTIVITY_RESUMED -> ForegroundUsageEventType.ACTIVITY_RESUMED
            UsageEvents.Event.ACTIVITY_PAUSED -> ForegroundUsageEventType.ACTIVITY_PAUSED
            UsageEvents.Event.ACTIVITY_STOPPED -> ForegroundUsageEventType.ACTIVITY_STOPPED
            UsageEvents.Event.SCREEN_NON_INTERACTIVE ->
                ForegroundUsageEventType.SCREEN_NON_INTERACTIVE
            UsageEvents.Event.KEYGUARD_SHOWN -> ForegroundUsageEventType.KEYGUARD_SHOWN
            else -> return null
        }
        val activity = when (type) {
            ForegroundUsageEventType.ACTIVITY_RESUMED,
            ForegroundUsageEventType.ACTIVITY_PAUSED,
            ForegroundUsageEventType.ACTIVITY_STOPPED -> ForegroundActivityIdentity(
                packageName = packageName,
                className = className
            )
            else -> null
        }
        return ForegroundUsageEvent(type, activity)
    }

    companion object {
        // The target is launched immediately before viewing starts; five minutes covers
        // launch/activity-event delivery delay without rescanning an entire day.
        private const val INITIAL_LOOKBACK_MILLIS = 5L * 60L * 1_000L

        fun hasUsageAccess(context: Context): Boolean {
            val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
            return appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            ) == AppOpsManager.MODE_ALLOWED
        }

    }
}
