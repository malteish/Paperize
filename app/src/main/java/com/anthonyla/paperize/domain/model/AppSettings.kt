package com.anthonyla.paperize.domain.model

/**
 * Domain model for App Settings (theme, preferences, etc.)
 */
data class AppSettings(
    val darkMode: Boolean? = true,  // true = dark (default), false = light, null = follow system
    val dynamicTheming: Boolean = false,
    val animate: Boolean = true,
    val firstLaunch: Boolean = true,
    /**
     * SAF tree URI of the folder the copy-to-premium widget saves wallpapers into,
     * or null while no folder has been picked. Stored as a string because that is what
     * DataStore persists and what a persisted URI permission is taken on.
     */
    val premiumFolderUri: String? = null
) {
    companion object {
        fun default() = AppSettings()
    }
}
