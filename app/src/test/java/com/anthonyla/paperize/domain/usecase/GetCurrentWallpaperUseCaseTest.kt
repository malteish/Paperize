package com.anthonyla.paperize.domain.usecase

import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.domain.model.ScheduleSettings
import com.anthonyla.paperize.domain.model.Wallpaper
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.domain.repository.WallpaperRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GetCurrentWallpaperUseCaseTest {

    private val wallpapers = mockk<WallpaperRepository>()
    private val settings = mockk<SettingsRepository>()
    private val useCase = GetCurrentWallpaperUseCase(wallpapers, settings)

    private fun wallpaper(id: String) = Wallpaper(
        id = id, albumId = "a", uri = "content://$id", fileName = "$id.jpg", dateModified = 0
    )

    private fun given(mode: WallpaperMode, schedule: ScheduleSettings) {
        coEvery { settings.getWallpaperMode() } returns mode
        coEvery { settings.getScheduleSettings() } returns schedule
        coEvery { wallpapers.getCurrentWallpaper(any(), any()) } returns null
    }

    @Test
    fun `static mode prefers the home record`() = runTest {
        given(WallpaperMode.STATIC, ScheduleSettings(homeAlbumId = "h", lockAlbumId = "l"))
        coEvery { wallpapers.getCurrentWallpaper("h", ScreenType.HOME) } returns wallpaper("home")
        coEvery { wallpapers.getCurrentWallpaper("l", ScreenType.LOCK) } returns wallpaper("lock")

        assertEquals("home", useCase()?.id)
    }

    @Test
    fun `static mode falls back to BOTH and then to the lock album`() = runTest {
        given(WallpaperMode.STATIC, ScheduleSettings(homeAlbumId = "h", lockAlbumId = "l"))
        coEvery { wallpapers.getCurrentWallpaper("l", ScreenType.LOCK) } returns wallpaper("lock")
        assertEquals("lock", useCase()?.id)

        coEvery { wallpapers.getCurrentWallpaper("h", ScreenType.BOTH) } returns wallpaper("both")
        assertEquals("both", useCase()?.id)
    }

    @Test
    fun `live mode reads only the live album`() = runTest {
        given(WallpaperMode.LIVE, ScheduleSettings(homeAlbumId = "h", liveAlbumId = "v"))
        coEvery { wallpapers.getCurrentWallpaper("h", ScreenType.HOME) } returns wallpaper("home")
        assertNull(useCase())

        coEvery { wallpapers.getCurrentWallpaper("v", ScreenType.LIVE) } returns wallpaper("live")
        assertEquals("live", useCase()?.id)
    }

    @Test
    fun `no albums means no current wallpaper`() = runTest {
        given(WallpaperMode.STATIC, ScheduleSettings())
        assertNull(useCase())
    }
}
