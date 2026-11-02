package com.anthonyla.paperize.domain.usecase

import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.domain.model.Wallpaper
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.domain.repository.WallpaperRepository
import javax.inject.Inject

/**
 * Find the wallpaper Paperize most recently applied, or null when it has not applied one yet.
 *
 * Shared by everything that acts on "the current wallpaper": the in-app open-in-gallery
 * button, the open-image widget and the copy-to-premium widget.
 *
 * In live mode the live album's record wins. In static mode the home screen is preferred and
 * the lock screen is the fallback. Wallpapers applied to both screens at once are recorded
 * under [ScreenType.BOTH], so each screen checks its own type first and then BOTH.
 */
class GetCurrentWallpaperUseCase @Inject constructor(
    private val wallpaperRepository: WallpaperRepository,
    private val settingsRepository: SettingsRepository
) {
    suspend operator fun invoke(): Wallpaper? {
        val settings = settingsRepository.getScheduleSettings()
        if (settingsRepository.getWallpaperMode() == WallpaperMode.LIVE) {
            return settings.liveAlbumId?.let {
                wallpaperRepository.getCurrentWallpaper(it, ScreenType.LIVE)
            }
        }
        settings.homeAlbumId?.let { albumId ->
            wallpaperRepository.getCurrentWallpaper(albumId, ScreenType.HOME)?.let { return it }
            wallpaperRepository.getCurrentWallpaper(albumId, ScreenType.BOTH)?.let { return it }
        }
        settings.lockAlbumId?.let { albumId ->
            wallpaperRepository.getCurrentWallpaper(albumId, ScreenType.LOCK)?.let { return it }
            wallpaperRepository.getCurrentWallpaper(albumId, ScreenType.BOTH)?.let { return it }
        }
        return null
    }
}
