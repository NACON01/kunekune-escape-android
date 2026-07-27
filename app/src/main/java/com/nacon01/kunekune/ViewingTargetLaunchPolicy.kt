package com.nacon01.kunekune

object ViewingTargetLaunchPolicy {
    const val MAX_AUTOMATIC_ATTEMPTS = 3
    const val RETRY_DELAY_MILLIS = 1_000L

    fun orderedBrowserPackages(
        defaultBrowserPackage: String?,
        installedBrowserPackages: List<String>
    ): List<String> {
        val installed = installedBrowserPackages.distinct()
        val preferred = defaultBrowserPackage?.takeIf { it in installed }
        return listOfNotNull(preferred) + installed.filterNot { it == preferred }
    }
}
