package com.anthonyla.paperize.presentation.screens.wallpaper

/**
 * UI state of the "Save to premium" action
 */
sealed interface PremiumCopyState {
    /** Nothing in flight - the card shows its regular description */
    data object Idle : PremiumCopyState

    /** A copy is running - the card shows a progress indicator */
    data object Copying : PremiumCopyState

    /** The wallpaper was copied (or was already there) */
    data class Success(val message: String) : PremiumCopyState

    /** The copy failed - [message] explains why */
    data class Error(val message: String) : PremiumCopyState
}
