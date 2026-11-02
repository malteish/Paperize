package com.anthonyla.paperize.core.util

/**
 * Decides how to bring the wallpapers stored for one folder in line with a fresh scan of it.
 *
 * The scan already lists every image file below the folder, so comparing it with the stored
 * URIs finds both new and deleted files without a per-file query.
 */
object FolderSyncPlanner {

    data class Plan(
        /** Scanned URIs that are not stored yet. */
        val toAdd: Set<String>,
        /** Stored URIs that the scan no longer found. */
        val toRemove: Set<String>
    )

    /**
     * @param storedUris URIs currently stored for the folder.
     * @param scannedUris URIs found by the scan.
     * @param scanComplete false if any directory could not be listed. Then nothing is removed,
     *   because a missing URI may only sit in a directory the scan could not read.
     */
    fun plan(storedUris: Set<String>, scannedUris: Set<String>, scanComplete: Boolean): Plan =
        Plan(
            toAdd = scannedUris - storedUris,
            toRemove = if (scanComplete) storedUris - scannedUris else emptySet()
        )
}
