package com.nacon01.kunekune

object PictureInPicturePermission {
    fun shouldOpenInitialSetup(packageName: String, guidanceShown: Boolean): Boolean =
        packageName.isNotBlank() && !guidanceShown
}
