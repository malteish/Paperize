package com.anthonyla.paperize.domain.model

/**
 * Domain model for App Settings (theme, preferences, etc.)
 */
data class AppSettings(
    val darkMode: Boolean? = null,  // null = system default, true = dark, false = light
    val dynamicTheming: Boolean = false,
    val animate: Boolean = true,
    val firstLaunch: Boolean = true,
    // Tree URI of the folder that "Save to premium" copies wallpapers into; null = not configured
    val premiumFolderUri: String? = null
) {
    companion object {
        fun default() = AppSettings()
    }
}
