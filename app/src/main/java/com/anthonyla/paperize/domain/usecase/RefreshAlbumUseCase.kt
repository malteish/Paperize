package com.anthonyla.paperize.domain.usecase

import androidx.core.net.toUri
import com.anthonyla.paperize.core.Result
import com.anthonyla.paperize.domain.repository.AlbumRepository
import com.anthonyla.paperize.domain.repository.WallpaperRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Brings one album in line with the storage it points to.
 *
 * 1. Removes folders that are gone or no longer readable.
 * 2. Checks wallpapers added directly (one query each; usually few).
 * 3. Rescans each folder and compares the result with the database: one query per directory,
 *    no per-file checks, so large libraries stay cheap.
 *
 * Queues are cleared when new wallpapers appear so the next rebuild includes them; removed
 * wallpapers leave the queue through the foreign-key cascade.
 */
class RefreshAlbumUseCase @Inject constructor(
    private val wallpaperRepository: WallpaperRepository,
    private val albumRepository: AlbumRepository
) {
    data class Summary(
        val added: Int,
        val removed: Int,
        /** Steps or folders that failed or could only be scanned partly. */
        val problems: Int
    )

    suspend operator fun invoke(albumId: String): Summary {
        var added = 0
        var removed = 0
        var problems = 0

        when (val result = albumRepository.validateAndRemoveInvalidFolders(albumId)) {
            is Result.Success -> removed += result.data
            else -> problems++
        }

        when (val result = wallpaperRepository.validateDirectWallpapers(albumId)) {
            is Result.Success -> removed += result.data
            else -> problems++
        }

        val folders = albumRepository.getAlbumById(albumId).first()?.folders.orEmpty()
        for (folder in folders) {
            currentCoroutineContext().ensureActive()
            when (val result = wallpaperRepository.syncFolderWallpapers(albumId, folder.id, folder.uri.toUri())) {
                is Result.Success -> {
                    added += result.data.added
                    removed += result.data.removed
                    if (!result.data.complete) problems++
                }
                else -> problems++
            }
        }

        if (added > 0) {
            wallpaperRepository.clearQueuesForAlbum(albumId)
            albumRepository.refreshFolderCovers(albumId)
        }
        if (removed > 0) {
            albumRepository.refreshAlbumCover(albumId)
        }

        return Summary(added, removed, problems)
    }
}
