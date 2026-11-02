package com.anthonyla.paperize.domain.model

/**
 * Outcome of syncing one folder's stored wallpapers with a fresh scan.
 *
 * @param complete false if some directory could not be listed, in which case nothing was removed.
 */
data class FolderSyncResult(
    val added: Int,
    val removed: Int,
    val complete: Boolean
)
