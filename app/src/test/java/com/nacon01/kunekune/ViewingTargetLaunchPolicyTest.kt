package com.nacon01.kunekune

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewingTargetLaunchPolicyTest {
    @Test
    fun triesDefaultBrowserFirstThenEachInstalledFallbackOnce() {
        assertEquals(
            listOf("browser.default", "browser.alt"),
            ViewingTargetLaunchPolicy.orderedBrowserPackages(
                defaultBrowserPackage = "browser.default",
                installedBrowserPackages = listOf(
                    "browser.alt",
                    "browser.default",
                    "browser.alt"
                )
            )
        )
    }

    @Test
    fun ignoresResolverThatIsNotAnInstalledBrowser() {
        assertEquals(
            listOf("browser.one", "browser.two"),
            ViewingTargetLaunchPolicy.orderedBrowserPackages(
                defaultBrowserPackage = "android",
                installedBrowserPackages = listOf("browser.one", "browser.two")
            )
        )
    }
}
