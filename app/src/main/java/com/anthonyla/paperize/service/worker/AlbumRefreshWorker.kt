package com.anthonyla.paperize.service.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.anthonyla.paperize.domain.repository.AlbumRepository
import com.anthonyla.paperize.domain.usecase.RefreshAlbumUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Brings albums in line with their folders: adds new image files, removes deleted ones.
 *
 * Runs once a day (see WallpaperScheduler.scheduleAlbumRefresh) and when the user asks for a
 * rescan of one album ([KEY_ALBUM_ID] set, see AlbumRefreshScheduler).
 */
@HiltWorker
class AlbumRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val albumRepository: AlbumRepository,
    private val refreshAlbumUseCase: RefreshAlbumUseCase
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val onlyAlbumId = inputData.getString(KEY_ALBUM_ID)
            val albumIds = if (onlyAlbumId != null) {
                listOf(onlyAlbumId)
            } else {
                albumRepository.getAllAlbums().first().map { it.id }
            }
            Log.d(TAG, "Starting album refresh for ${albumIds.size} album(s)")
            val started = System.currentTimeMillis()

            var added = 0
            var removed = 0
            var problems = 0
            albumIds.forEach { albumId ->
                val summary = refreshAlbumUseCase(albumId)
                added += summary.added
                removed += summary.removed
                problems += summary.problems
            }

            Log.d(
                TAG,
                "Album refresh completed in ${System.currentTimeMillis() - started} ms: " +
                    "added $added, removed $removed, problems $problems"
            )
            // Partial problems are retried by the next scheduled run, not by WorkManager backoff
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error during album refresh", e)
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "AlbumRefreshWorker"
        const val KEY_ALBUM_ID = "album_id"
    }
}
