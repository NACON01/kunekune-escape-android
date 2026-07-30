package com.nacon01.kunekune

class PendingViewingLaunch {
    private var targetId: String? = null
    private var ready = false

    @Synchronized
    fun prepare(newTargetId: String?) {
        targetId = newTargetId
        ready = false
    }

    @Synchronized
    fun prepare(newTarget: ViewingTarget?) = prepare(newTarget?.name)

    @Synchronized
    fun markReady() {
        ready = true
    }

    @Synchronized
    fun pendingTargetIdIfReady(): String? = targetId?.takeIf { ready }

    /** Compatibility view for the pre-catalog YouTube/browser workflow. */
    @Synchronized
    fun pendingTargetIfReady(): ViewingTarget? = targetId
        ?.takeIf { ready }
        ?.let { value -> ViewingTarget.entries.firstOrNull { it.name == value } }

    @Synchronized
    fun completeTarget(expectedTargetId: String, launchedPackage: String): Boolean {
        if (!ready || targetId != expectedTargetId || launchedPackage.isBlank()) return false
        targetId = null
        ready = false
        return true
    }

    @Synchronized
    fun complete(expectedTarget: ViewingTarget, launchedPackage: String): Boolean =
        completeTarget(expectedTarget.name, launchedPackage)

    @Deprecated("A launched package is required for usage-gated viewing")
    @Synchronized
    fun complete(expectedTarget: ViewingTarget): Boolean = completeTarget(expectedTarget.name, "legacy")

    @Synchronized
    fun isPending(): Boolean = targetId != null

    @Synchronized
    fun clear() {
        targetId = null
        ready = false
    }
}
