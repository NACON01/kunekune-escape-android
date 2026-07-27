package com.nacon01.kunekune

class PendingViewingLaunch {
    private var target: ViewingTarget? = null
    private var ready = false

    @Synchronized
    fun prepare(newTarget: ViewingTarget?) {
        target = newTarget
        ready = false
    }

    @Synchronized
    fun markReady() {
        ready = true
    }

    @Synchronized
    fun pendingTargetIfReady(): ViewingTarget? = target.takeIf { ready }

    @Synchronized
    fun complete(expectedTarget: ViewingTarget, launchedPackage: String): Boolean {
        if (!ready || target != expectedTarget || launchedPackage.isBlank()) return false
        target = null
        ready = false
        return true
    }

    @Deprecated("A launched package is required for usage-gated viewing")
    @Synchronized
    fun complete(expectedTarget: ViewingTarget): Boolean = complete(expectedTarget, "legacy")

    @Synchronized
    fun isPending(): Boolean = target != null

    @Synchronized
    fun clear() {
        target = null
        ready = false
    }
}
