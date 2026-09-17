package com.anthonyla.paperize.core

/**
 * Exception thrown when an album has no wallpapers available
 */
class EmptyAlbumException(message: String) : Exception(message)

/**
 * Exception thrown when no valid wallpaper can be found after retries
 */
class NoValidWallpaperException(message: String) : Exception(message)

/**
 * Exception thrown when the premium folder has not been configured in settings
 */
class PremiumFolderNotConfiguredException(message: String) : Exception(message)

/**
 * Exception thrown when the configured premium folder can no longer be written to
 * (deleted, unmounted, or the persisted URI permission was revoked)
 */
class PremiumFolderUnavailableException(message: String) : Exception(message)

/**
 * Exception thrown when no wallpaper has been applied yet, so there is nothing to copy
 */
class NoCurrentWallpaperException(message: String) : Exception(message)
