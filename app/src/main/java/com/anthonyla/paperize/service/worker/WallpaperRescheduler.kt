package com.anthonyla.paperize.service.worker

import android.util.Log
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rebuilds the WorkManager schedule from the persisted schedule settings.
 *
 * Shared by [com.anthonyla.paperize.service.alarm.BootReceiver] (after reboot) and
 * [com.anthonyla.paperize.service.widget.PauseResumeWidgetProvider] (on resume), both of
 * which must restore the schedule without going through a ViewModel.
 */
@Singleton
class WallpaperRescheduler @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val wallpaperScheduler: WallpaperScheduler
) {
    companion object {
        private const val TAG = "WallpaperRescheduler"
    }

    /**
     * Schedule wallpaper changes according to the saved settings. Does nothing unless the
     * changer is enabled and the required album(s) are selected.
     *
     * @param onlyIfNotScheduled If true, don't reschedule work that is already scheduled
     * (used on boot, where existing work should keep its timing).
     */
    suspend fun rescheduleFromSettings(onlyIfNotScheduled: Boolean = false) {
        val settings = settingsRepository.getScheduleSettings()

        if (!settings.enableChanger) {
            Log.d(TAG, "Wallpaper changer disabled, not scheduling")
            return
        }

        // Get wallpaper mode to determine scheduling type
        val wallpaperMode = settingsRepository.getWallpaperMode()

        if (wallpaperMode == WallpaperMode.LIVE) {
            // LIVE mode: schedule live wallpaper changes
            if (settings.liveAlbumId != null && settings.liveIntervalMinutes > 0) {
                wallpaperScheduler.scheduleWallpaperChange(
                    ScreenType.LIVE,
                    settings.liveIntervalMinutes
                )
                Log.d(TAG, "Live wallpaper changes scheduled")
            } else {
                Log.d(TAG, "Live mode but no album or interval, not scheduling")
            }
        } else {
            // STATIC mode: existing logic
            // Check if we have all required albums before scheduling
            val homeActive = settings.homeEnabled && settings.homeAlbumId != null
            val lockActive = settings.lockEnabled && settings.lockAlbumId != null
            val hasRequiredAlbums = when {
                settings.homeEnabled && settings.lockEnabled -> homeActive && lockActive
                settings.homeEnabled -> homeActive
                settings.lockEnabled -> lockActive
                else -> false
            }

            if (hasRequiredAlbums) {
                // Reschedule wallpaper changes with WorkManager
                val homeInterval: Int
                val lockInterval: Int

                if (settings.separateSchedules) {
                    // Separate schedules for home and lock
                    homeInterval = if (settings.homeEnabled) settings.homeIntervalMinutes else 0
                    lockInterval = if (settings.lockEnabled) settings.lockIntervalMinutes else 0
                } else {
                    // Same interval for both (enabled screens)
                    val interval = settings.homeIntervalMinutes
                    homeInterval = if (settings.homeEnabled) interval else 0
                    lockInterval = if (settings.lockEnabled) interval else 0
                }

                // Determine if screens should be synchronized
                val shouldSync = settings.homeEnabled && settings.lockEnabled &&
                                settings.homeAlbumId != null &&
                                settings.homeAlbumId == settings.lockAlbumId &&
                                !settings.separateSchedules

                wallpaperScheduler.scheduleWallpaperChanges(
                    homeIntervalMinutes = homeInterval,
                    lockIntervalMinutes = lockInterval,
                    synchronized = shouldSync,
                    onlyIfNotScheduled = onlyIfNotScheduled
                )

                Log.d(TAG, "Wallpaper changes rescheduled successfully")
            } else {
                Log.d(TAG, "Wallpaper changer enabled but required albums not selected, not scheduling")
            }
        }
    }
}
