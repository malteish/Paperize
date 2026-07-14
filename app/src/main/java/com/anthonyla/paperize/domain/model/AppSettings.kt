package com.anthonyla.paperize.domain.model

/**
 * Domain model for App Settings (theme, preferences, etc.)
 */
data class AppSettings(
    val darkMode: Boolean? = true,  // true = dark (default), false = light, null = follow system
    val dynamicTheming: Boolean = false,
    val animate: Boolean = true,
    val firstLaunch: Boolean = true
) {
    companion object {
        fun default() = AppSettings()
    }
}
