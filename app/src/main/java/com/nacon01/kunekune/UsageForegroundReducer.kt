package com.nacon01.kunekune

enum class ForegroundUsageEventType {
    ACTIVITY_RESUMED,
    ACTIVITY_PAUSED,
    ACTIVITY_STOPPED,
    SCREEN_NON_INTERACTIVE,
    KEYGUARD_SHOWN
}

data class ForegroundActivityIdentity(
    val packageName: String,
    val className: String? = null
)

data class ForegroundUsageEvent(
    val type: ForegroundUsageEventType,
    val activity: ForegroundActivityIdentity? = null
)

data class ForegroundUsageObservation(
    val packageName: String?,
    val differentPackageSincePreviousObservation: Boolean,
    val screenNonInteractiveSincePreviousObservation: Boolean,
    val reconciliationPending: Boolean
)

/** Pure UsageEvents state reducer; it has no Android dependencies and is safe to unit test. */
class UsageForegroundReducer {
    private var currentForeground: ForegroundActivityIdentity? = null
    private var differentPackageSinceObservation = false
    private var screenNonInteractiveSinceObservation = false

    fun apply(event: ForegroundUsageEvent) {
        when (event.type) {
            ForegroundUsageEventType.ACTIVITY_RESUMED -> {
                val activity = event.activity ?: return
                val currentPackage = currentForeground?.packageName
                if (currentPackage != null && currentPackage != activity.packageName) {
                    differentPackageSinceObservation = true
                }
                currentForeground = activity
                reconciliationPending = false
            }

            ForegroundUsageEventType.ACTIVITY_PAUSED -> {
                if (event.activity == currentForeground) reconciliationPending = true
            }

            ForegroundUsageEventType.ACTIVITY_STOPPED -> Unit

            ForegroundUsageEventType.SCREEN_NON_INTERACTIVE,
            ForegroundUsageEventType.KEYGUARD_SHOWN -> {
                screenNonInteractiveSinceObservation = true
                reconciliationPending = false
            }
        }
    }

    fun consumeObservation(): ForegroundUsageObservation {
        val observation = ForegroundUsageObservation(
            packageName = currentForeground?.packageName,
            differentPackageSincePreviousObservation = differentPackageSinceObservation,
            screenNonInteractiveSincePreviousObservation = screenNonInteractiveSinceObservation,
            reconciliationPending = this.reconciliationPending
        )
        differentPackageSinceObservation = false
        screenNonInteractiveSinceObservation = false
        return observation
    }

    fun reset() {
        currentForeground = null
        differentPackageSinceObservation = false
        screenNonInteractiveSinceObservation = false
        reconciliationPending = false
    }

    private var reconciliationPending = false
}

data class UsageQueryWindow(
    val beginMillis: Long,
    val endMillis: Long,
    val clockRolledBack: Boolean
)

/** Tracks [begin, end) query windows and detects wall-clock rollback without Android APIs. */
class UsageEventQueryWindow(private val initialLookbackMillis: Long) {
    private var previousExclusiveEndMillis: Long? = null

    fun next(nowMillis: Long): UsageQueryWindow? {
        val endMillis = nowMillis.coerceAtLeast(0L)
        val previousEnd = previousExclusiveEndMillis
        if (previousEnd != null && endMillis == previousEnd) return null
        val clockRolledBack = previousEnd != null && endMillis < previousEnd
        val beginMillis = if (clockRolledBack || previousEnd == null) {
            (endMillis - initialLookbackMillis).coerceAtLeast(0L)
        } else {
            previousEnd
        }
        return UsageQueryWindow(beginMillis, endMillis, clockRolledBack)
    }

    fun commit(endMillis: Long) {
        previousExclusiveEndMillis = endMillis.coerceAtLeast(0L)
    }

    fun reset() {
        previousExclusiveEndMillis = null
    }
}
