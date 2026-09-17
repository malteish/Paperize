package com.anthonyla.paperize.core.util

/**
 * File name helpers for copying wallpapers into user-picked folders.
 *
 * Pure Kotlin - no Android dependencies - so the naming rules can be unit tested.
 */
object FileNames {

    /** Fallback name used when a wallpaper has no usable file name */
    const val FALLBACK_FILE_NAME = "wallpaper.jpg"

    /** Fallback MIME type - providers keep the supplied extension for this type */
    const val FALLBACK_MIME_TYPE = "application/octet-stream"

    /** Upper bound for the " (n)" suffix before falling back to a timestamp */
    private const val MAX_NAME_ATTEMPTS = 1000

    /**
     * Map a file name to an image MIME type based on its extension.
     *
     * Returns [FALLBACK_MIME_TYPE] for unknown extensions - Storage Access Framework
     * providers then keep the extension of the supplied display name as-is.
     */
    fun mimeTypeForFileName(fileName: String): String =
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "avif" -> "image/avif"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            "bmp" -> "image/bmp"
            "gif" -> "image/gif"
            "tiff", "tif" -> "image/tiff"
            "svg" -> "image/svg+xml"
            else -> FALLBACK_MIME_TYPE
        }

    /**
     * Find a file name that is not taken yet in the destination folder.
     *
     * Appends " (1)", " (2)", ... before the extension, mirroring how file managers
     * disambiguate copies. If every candidate is taken, a timestamp suffix is used.
     *
     * @param fileName desired name, e.g. "sunset.jpg"
     * @param exists returns true when a file with the given name already exists
     * @param timestamp value used for the last-resort suffix
     */
    fun uniqueFileName(
        fileName: String,
        timestamp: Long = System.currentTimeMillis(),
        exists: (String) -> Boolean
    ): String {
        if (!exists(fileName)) return fileName

        val extension = fileName.substringAfterLast('.', "")
        val base = if (extension.isEmpty()) fileName else fileName.dropLast(extension.length + 1)
        val suffix = if (extension.isEmpty()) "" else ".$extension"

        for (attempt in 1 until MAX_NAME_ATTEMPTS) {
            val candidate = "$base ($attempt)$suffix"
            if (!exists(candidate)) return candidate
        }

        return "$base ($timestamp)$suffix"
    }
}
