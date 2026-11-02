package com.anthonyla.paperize.service.worker

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

/**
 * Starts a rescan of one album on the user's request (the daily refresh of all albums is
 * scheduled by WallpaperScheduler).
 *
 * Unique work per album with [ExistingWorkPolicy.KEEP] ignores repeated taps while a rescan of
 * that album is still running.
 */
object AlbumRefreshScheduler {
    private const val UNIQUE_WORK_PREFIX = "album_rescan_"
    internal const val WORK_TAG = "manual_album_rescan"

    /** Unique work name of the removed rescan-on-every-app-start job. */
    private const val LEGACY_FOREGROUND_WORK_NAME = "album_refresh_on_foreground"

    fun enqueueRescan(context: Context, albumId: String) {
        val request = OneTimeWorkRequestBuilder<AlbumRefreshWorker>()
            .setInputData(workDataOf(AlbumRefreshWorker.KEY_ALBUM_ID to albumId))
            .addTag(WORK_TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_PREFIX + albumId,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun cancelLegacyForegroundRefresh(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(LEGACY_FOREGROUND_WORK_NAME)
    }
}
