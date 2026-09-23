package com.anthonyla.paperize.domain.usecase

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.core.util.PremiumFolderNaming
import com.anthonyla.paperize.domain.model.ScheduleSettings
import com.anthonyla.paperize.domain.model.Wallpaper
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.domain.repository.WallpaperRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Outcome of a premium-folder copy, so callers can report exactly what happened. */
sealed interface CopyToPremiumFolderResult {
    /** The wallpaper was copied and now exists in the premium folder under [name]. */
    data class Copied(val name: String) : CopyToPremiumFolderResult

    /** The same image was already in the folder; nothing was written. */
    data class AlreadyPresent(val name: String) : CopyToPremiumFolderResult

    /** No premium folder has been picked in settings yet. */
    data object NoFolderConfigured : CopyToPremiumFolderResult

    /** The picked folder is gone, or its persisted permission was revoked. */
    data object FolderUnavailable : CopyToPremiumFolderResult

    /** Paperize has not applied a wallpaper yet, so there is nothing to copy. */
    data object NoCurrentWallpaper : CopyToPremiumFolderResult

    /** The copy itself failed (read error, no space, provider refused the write, …). */
    data class Failed(val exception: Throwable) : CopyToPremiumFolderResult
}

/**
 * Copy the wallpaper Paperize currently has applied into the user's premium folder.
 *
 * Backs the copy-to-premium home-screen widget: a way to keep the image that just showed up
 * on the home screen, without hunting for it in the album afterwards. The premium folder is
 * a Storage Access Framework tree the user picks in settings, so the copy goes through
 * [DocumentsContract] rather than [java.io.File] and needs no storage permission of its own.
 *
 * The source image is copied byte for byte — the wallpaper's effects (blur, darken, …) are
 * applied when the wallpaper is set and are deliberately not baked into the saved copy.
 */
class CopyToPremiumFolderUseCase @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val wallpaperRepository: WallpaperRepository,
    private val settingsRepository: SettingsRepository
) {
    suspend operator fun invoke(): CopyToPremiumFolderResult {
        val folderUriString = settingsRepository.getAppSettings().premiumFolderUri
        if (folderUriString.isNullOrEmpty()) return CopyToPremiumFolderResult.NoFolderConfigured

        val wallpaper = resolveCurrentWallpaper()
            ?: return CopyToPremiumFolderResult.NoCurrentWallpaper

        return try {
            copyToFolder(wallpaper, folderUriString.toUri())
        } catch (e: SecurityException) {
            // Persisted permission revoked, e.g. the SD card was removed or the folder deleted.
            Log.w(TAG, "Lost access to the premium folder", e)
            CopyToPremiumFolderResult.FolderUnavailable
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy wallpaper to the premium folder", e)
            CopyToPremiumFolderResult.Failed(e)
        }
    }

    /**
     * Find the most relevant "current wallpaper" record, mirroring the resolution the home
     * screen preview uses: wallpapers applied to both screens at once are recorded under
     * [ScreenType.BOTH], so each screen checks its own type first and then falls back to BOTH.
     */
    private suspend fun resolveCurrentWallpaper(): Wallpaper? {
        val settings = settingsRepository.getScheduleSettings()
        if (settingsRepository.getWallpaperMode() == WallpaperMode.LIVE) {
            return settings.liveAlbumId?.let {
                wallpaperRepository.getCurrentWallpaper(it, ScreenType.LIVE)
            }
        }
        return resolveStaticWallpaper(settings)
    }

    private suspend fun resolveStaticWallpaper(settings: ScheduleSettings): Wallpaper? {
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

    private fun copyToFolder(wallpaper: Wallpaper, treeUri: Uri): CopyToPremiumFolderResult {
        val treeDocumentId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (_: IllegalArgumentException) {
            return CopyToPremiumFolderResult.FolderUnavailable
        }
        val folderUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId)

        val sourceUri = wallpaper.uri.toUri()
        val sourceSize = querySourceSize(sourceUri)
        val existing = listFolderContents(treeUri, treeDocumentId)
            ?: return CopyToPremiumFolderResult.FolderUnavailable

        val plan = PremiumFolderNaming.planCopy(
            sourceName = PremiumFolderNaming.prefixedName(
                wallpaper.displayFileName,
                sourceParentFolder(sourceUri)
            ),
            sourceSize = sourceSize,
            existing = existing
        )
        if (plan is PremiumFolderNaming.CopyPlan.AlreadyPresent) {
            return CopyToPremiumFolderResult.AlreadyPresent(plan.name)
        }
        val targetName = (plan as PremiumFolderNaming.CopyPlan.Create).name

        val targetUri = DocumentsContract.createDocument(
            context.contentResolver,
            folderUri,
            mimeTypeOf(sourceUri, targetName),
            targetName
        ) ?: return CopyToPremiumFolderResult.FolderUnavailable

        try {
            context.contentResolver.openInputStream(sourceUri).use { input ->
                if (input == null) {
                    // The album still lists the wallpaper, but the file behind it is gone.
                    deleteQuietly(targetUri)
                    return CopyToPremiumFolderResult.Failed(
                        IllegalStateException("Cannot read $sourceUri")
                    )
                }
                context.contentResolver.openOutputStream(targetUri).use { output ->
                    if (output == null) {
                        deleteQuietly(targetUri)
                        return CopyToPremiumFolderResult.FolderUnavailable
                    }
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            // Never leave a half-written image behind — it would be picked up as a real file
            // by the next copy's duplicate check and by anything scanning the folder.
            deleteQuietly(targetUri)
            throw e
        }

        // The document provider may have adjusted the name (a taken name, an appended
        // extension), so report what actually landed rather than what was requested.
        return CopyToPremiumFolderResult.Copied(queryDisplayName(targetUri) ?: targetName)
    }

    /**
     * List the premium folder's direct children as name/size pairs for the duplicate check.
     * Returns null when the folder cannot be read at all, which means it is gone or its
     * permission was revoked.
     */
    private fun listFolderContents(
        treeUri: Uri,
        treeDocumentId: String
    ): List<PremiumFolderNaming.ExistingFile>? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE
        )
        return context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            buildList {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameColumn) ?: continue
                    val size = if (cursor.isNull(sizeColumn)) 0L else cursor.getLong(sizeColumn)
                    add(PremiumFolderNaming.ExistingFile(name, size))
                }
            }
        }
    }

    /** Byte size of the source image, or 0 when the provider does not report one. */
    private fun querySourceSize(uri: Uri): Long {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeColumn >= 0 && cursor.moveToFirst() && !cursor.isNull(sizeColumn)) {
                        return cursor.getLong(sizeColumn)
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the size of $uri", e)
        }
        return 0L
    }

    /** Parent folder of the source document, read from its path-shaped document id. */
    private fun sourceParentFolder(sourceUri: Uri): String? = try {
        PremiumFolderNaming.parentFolderName(DocumentsContract.getDocumentId(sourceUri))
    } catch (_: IllegalArgumentException) {
        null // Not a document URI
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        context.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (nameColumn >= 0 && cursor.moveToFirst()) cursor.getString(nameColumn) else null
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not read the name of $uri", e)
        null
    }

    /**
     * Resolve a concrete MIME type for the new document. The source provider's own type is
     * the most accurate; the file extension is the fallback, and JPEG the last resort, since
     * [DocumentsContract.createDocument] needs a concrete type rather than a wildcard one.
     */
    private fun mimeTypeOf(sourceUri: Uri, targetName: String): String {
        context.contentResolver.getType(sourceUri)
            ?.takeIf { it.startsWith("image/") }
            ?.let { return it }
        val extension = targetName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: FALLBACK_MIME_TYPE
    }

    private fun deleteQuietly(uri: Uri) {
        try {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (e: Exception) {
            Log.w(TAG, "Could not clean up the partially written copy $uri", e)
        }
    }

    companion object {
        private const val TAG = "CopyToPremiumFolder"
        private const val FALLBACK_MIME_TYPE = "image/jpeg"
    }
}
