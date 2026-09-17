package com.anthonyla.paperize.domain.usecase

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.anthonyla.paperize.R
import com.anthonyla.paperize.core.NoCurrentWallpaperException
import com.anthonyla.paperize.core.PremiumFolderNotConfiguredException
import com.anthonyla.paperize.core.PremiumFolderUnavailableException
import com.anthonyla.paperize.core.Result
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.core.util.FileNames
import com.anthonyla.paperize.domain.model.Wallpaper
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.domain.repository.WallpaperRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Use case to copy the currently applied wallpaper into the user's premium folder
 *
 * The original file is copied byte-for-byte - effects such as blur or darkening are
 * display-only and are deliberately not baked into the saved copy.
 *
 * Flow:
 * 1. Resolve the premium folder configured in settings
 * 2. Resolve the wallpaper that was applied last for the active mode/screen
 * 3. Copy it into the folder, disambiguating the file name if it is already taken
 */
class CopyCurrentWallpaperUseCase @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val wallpaperRepository: WallpaperRepository,
    private val settingsRepository: SettingsRepository
) {
    companion object {
        private const val TAG = "CopyCurrentWallpaper"
    }

    /**
     * Result of a successful copy
     *
     * @param fileName name the wallpaper was saved under in the premium folder
     * @param alreadyExisted true when an identical file was already present, so nothing was written
     */
    data class CopiedWallpaper(
        val fileName: String,
        val alreadyExisted: Boolean
    )

    suspend operator fun invoke(): Result<CopiedWallpaper> {
        val appSettings = settingsRepository.getAppSettings()
        val folderUri = appSettings.premiumFolderUri
            ?: return Result.Error(
                PremiumFolderNotConfiguredException(context.getString(R.string.premium_folder_not_configured))
            )

        val wallpaper = resolveCurrentWallpaper()
            ?: return Result.Error(
                NoCurrentWallpaperException(context.getString(R.string.premium_copy_no_current_wallpaper))
            )

        return withContext<Result<CopiedWallpaper>>(Dispatchers.IO) {
            try {
                val destination = DocumentFile.fromTreeUri(context, folderUri.toUri())
                if (destination == null || !destination.isDirectory || !destination.canWrite()) {
                    return@withContext Result.Error(
                        PremiumFolderUnavailableException(context.getString(R.string.premium_folder_unavailable))
                    )
                }

                copyInto(destination, wallpaper)
            } catch (e: SecurityException) {
                // Persisted URI permission was revoked (folder removed from the app's grants)
                Log.w(TAG, "Lost access to the premium folder", e)
                Result.Error(
                    PremiumFolderUnavailableException(context.getString(R.string.premium_folder_unavailable))
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy wallpaper to the premium folder", e)
                Result.Error(e, context.getString(R.string.premium_copy_failed))
            }
        }
    }

    /**
     * Find the wallpaper that is currently applied
     *
     * LIVE mode tracks a single album; STATIC mode prefers the home screen and falls back to
     * the lock screen when only the lock screen is driven by Paperize.
     */
    private suspend fun resolveCurrentWallpaper(): Wallpaper? {
        val mode = settingsRepository.getWallpaperMode()
        val schedule = settingsRepository.getScheduleSettings()

        return if (mode == WallpaperMode.LIVE) {
            schedule.liveAlbumId?.let { wallpaperRepository.getCurrentWallpaper(it, ScreenType.LIVE) }
        } else {
            val home = schedule.homeAlbumId
                ?.takeIf { schedule.homeEnabled }
                ?.let { wallpaperRepository.getCurrentWallpaper(it, ScreenType.HOME) }
            home ?: schedule.lockAlbumId
                ?.takeIf { schedule.lockEnabled }
                ?.let { wallpaperRepository.getCurrentWallpaper(it, ScreenType.LOCK) }
        }
    }

    private fun copyInto(destination: DocumentFile, wallpaper: Wallpaper): Result<CopiedWallpaper> {
        val sourceUri = wallpaper.uri.toUri()
        val source = DocumentFile.fromSingleUri(context, sourceUri)
        val sourceLength = source?.length() ?: 0L
        val desiredName = wallpaper.displayFileName.takeIf { it.isNotBlank() }
            ?: FileNames.FALLBACK_FILE_NAME

        // Pressing the button twice on the same wallpaper should not pile up copies
        val existing = destination.findFile(desiredName)
        if (existing != null && existing.isFile && sourceLength > 0L && existing.length() == sourceLength) {
            return Result.Success(CopiedWallpaper(desiredName, alreadyExisted = true))
        }

        val fileName = FileNames.uniqueFileName(desiredName) { candidate ->
            destination.findFile(candidate) != null
        }
        val mimeType = FileNames.mimeTypeForFileName(fileName)

        val target = destination.createFile(mimeType, fileName)
            ?: return Result.Error(
                PremiumFolderUnavailableException(context.getString(R.string.premium_folder_unavailable))
            )

        try {
            context.contentResolver.openInputStream(sourceUri).use { input ->
                if (input == null) {
                    throw IllegalStateException("Cannot read wallpaper ${wallpaper.uri}")
                }
                context.contentResolver.openOutputStream(target.uri).use { output ->
                    if (output == null) {
                        throw IllegalStateException("Cannot write to ${target.uri}")
                    }
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            // Don't leave a half-written file behind in the user's folder
            try {
                target.delete()
            } catch (deleteError: Exception) {
                Log.w(TAG, "Failed to clean up partial copy ${target.uri}", deleteError)
            }
            throw e
        }

        // The provider may rename the file (e.g. to avoid its own collisions)
        return Result.Success(CopiedWallpaper(target.name ?: fileName, alreadyExisted = false))
    }
}
