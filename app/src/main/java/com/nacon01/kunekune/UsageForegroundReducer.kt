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
    val activity: ForegroundActivityIdentity? = null,
    val timestampMillis: Long = 0L
)

data class ForegroundUsageObservation(
    val packageName: String?,
    val differentPackageSincePreviousObservation: Boolean,
    val screenNonInteractiveSincePreviousObservation: Boolean,
    val reconciliationPending: Boolean
)

/** Keeps one-shot interruptions observed before a successful reconciliation consume. */
fun ForegroundUsageObservation.mergeReconciledObservation(
    reconciled: ForegroundUsageObservation
): ForegroundUsageObservation = reconciled.copy(
    differentPackageSincePreviousObservation =
        differentPackageSincePreviousObservation ||
            reconciled.differentPackageSincePreviousObservation,
    screenNonInteractiveSincePreviousObservation =
        screenNonInteractiveSincePreviousObservation ||
            reconciled.screenNonInteractiveSincePreviousObservation
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

    /**
     * Applies the authoritative result of a bounded recent lifecycle rescan. A resume is
     * authoritative; pause, stop, and an incomplete history are deliberately not treated as
     * proof that the target is foreground.
     */
    fun reconcile(recentState: RecentForegroundLifecycleState) {
        if (recentState.status != ForegroundLifecycleStatus.RESUMED) {
            reconciliationPending = true
            return
        }
        val activity = recentState.activity ?: run {
            reconciliationPending = true
            return
        }
        val currentPackage = currentForeground?.packageName
        if (currentPackage != null && currentPackage != activity.packageName) {
            differentPackageSinceObservation = true
        }
        currentForeground = activity
        reconciliationPending = false
    }

    private var reconciliationPending = false
}

enum class ForegroundLifecycleStatus {
    NONE,
    RESUMED,
    PAUSED,
    STOPPED,
    AMBIGUOUS
}

data class RecentForegroundLifecycleState(
    val status: ForegroundLifecycleStatus,
    val activity: ForegroundActivityIdentity? = null
)

/** Pure, Android-free reducer for a bounded chronological lifecycle rescan. */
class RecentForegroundLifecycleReducer {
    private var currentForeground: ForegroundActivityIdentity? = null
    private var status = ForegroundLifecycleStatus.NONE

    fun apply(event: ForegroundUsageEvent) {
        when (event.type) {
            ForegroundUsageEventType.ACTIVITY_RESUMED -> {
                val activity = event.activity ?: run {
                    status = ForegroundLifecycleStatus.AMBIGUOUS
                    return
                }
                currentForeground = activity
                status = ForegroundLifecycleStatus.RESUMED
            }

            ForegroundUsageEventType.ACTIVITY_PAUSED -> {
                val activity = event.activity
                if (activity == null) {
                    status = ForegroundLifecycleStatus.AMBIGUOUS
                } else if (activity == currentForeground) {
                    status = ForegroundLifecycleStatus.PAUSED
                }
            }

            ForegroundUsageEventType.ACTIVITY_STOPPED -> {
                val activity = event.activity
                if (activity == null) {
                    status = ForegroundLifecycleStatus.AMBIGUOUS
                } else if (activity == currentForeground) {
                    status = ForegroundLifecycleStatus.STOPPED
                }
            }

            ForegroundUsageEventType.SCREEN_NON_INTERACTIVE,
            ForegroundUsageEventType.KEYGUARD_SHOWN -> Unit
        }
    }

    fun snapshot(): RecentForegroundLifecycleState = RecentForegroundLifecycleState(
        status = status,
        activity = currentForeground
    )
}

fun UsageForegroundReducer.reconcileRecentLifecycle(
    events: Iterable<ForegroundUsageEvent>
) {
    val recentReducer = RecentForegroundLifecycleReducer()
    val orderedEvents = events.withIndex()
        .filter { it.value.type.isActivityLifecycleEvent() }
        .sortedWith(
            compareBy<IndexedValue<ForegroundUsageEvent>>(
                { it.value.timestampMillis },
                { it.index }
            )
        )
    if (orderedEvents.isEmpty()) return
    orderedEvents.forEach { recentReducer.apply(it.value) }
    reconcile(recentReducer.snapshot())
}

private fun ForegroundUsageEventType.isActivityLifecycleEvent(): Boolean = when (this) {
    ForegroundUsageEventType.ACTIVITY_RESUMED,
    ForegroundUsageEventType.ACTIVITY_PAUSED,
    ForegroundUsageEventType.ACTIVITY_STOPPED -> true
    ForegroundUsageEventType.SCREEN_NON_INTERACTIVE,
    ForegroundUsageEventType.KEYGUARD_SHOWN -> false
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
