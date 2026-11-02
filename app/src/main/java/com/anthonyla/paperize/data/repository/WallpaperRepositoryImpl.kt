package com.anthonyla.paperize.data.repository

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.anthonyla.paperize.core.Result
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.WallpaperSourceType
import com.anthonyla.paperize.core.util.generateId
import com.anthonyla.paperize.core.util.isValid
import com.anthonyla.paperize.core.util.FolderSyncPlanner
import com.anthonyla.paperize.core.util.scanFolderTree
import com.anthonyla.paperize.data.database.dao.WallpaperCurrentDao
import com.anthonyla.paperize.data.database.dao.WallpaperDao
import com.anthonyla.paperize.data.database.dao.WallpaperQueueDao
import com.anthonyla.paperize.data.database.entities.WallpaperCurrentEntity
import com.anthonyla.paperize.data.mapper.toDomainModel
import com.anthonyla.paperize.data.mapper.toDomainModels
import com.anthonyla.paperize.data.mapper.toEntity
import com.anthonyla.paperize.domain.model.FolderSyncResult
import com.anthonyla.paperize.domain.model.Wallpaper
import com.anthonyla.paperize.domain.repository.WallpaperRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.ConcurrentHashMap

/**
 * Implementation of WallpaperRepository
 */
@Singleton
class WallpaperRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val wallpaperDao: WallpaperDao,
    private val wallpaperQueueDao: WallpaperQueueDao,
    private val wallpaperCurrentDao: WallpaperCurrentDao
) : WallpaperRepository {

    private val contentResolver: ContentResolver = context.contentResolver

    /**
     * Mutexes to synchronize queue rebuild operations per (albumId, screenType)
     * Prevents duplicate rebuilds when multiple threads detect empty queue simultaneously
     */
    private val queueRebuildMutexes = ConcurrentHashMap<String, Mutex>()

    override fun getWallpapersByAlbum(albumId: String): Flow<List<Wallpaper>> =
        wallpaperDao.getWallpapersByAlbum(albumId).map { it.toDomainModels() }

    override fun getDirectWallpapersByAlbum(albumId: String): Flow<List<Wallpaper>> =
        wallpaperDao.getDirectWallpapersByAlbum(albumId).map { it.toDomainModels() }

    override fun getWallpapersByFolder(folderId: String): Flow<List<Wallpaper>> =
        wallpaperDao.getWallpapersByFolder(folderId).map { it.toDomainModels() }

    override suspend fun getWallpaperById(wallpaperId: String): Wallpaper? =
        wallpaperDao.getWallpaperById(wallpaperId)?.toDomainModel()

    override suspend fun addWallpaper(wallpaper: Wallpaper): Result<Unit> {
        return try {
            wallpaperDao.insertWallpaper(wallpaper.toEntity())
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    override suspend fun updateWallpaper(wallpaper: Wallpaper): Result<Unit> {
        return try {
            wallpaperDao.updateWallpaper(wallpaper.toEntity())
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    override suspend fun deleteWallpaper(wallpaperId: String): Result<Unit> {
        return try {
            wallpaperDao.deleteWallpaperById(wallpaperId)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    override suspend fun deleteWallpapers(wallpaperIds: List<String>): Result<Unit> {
        return try {
            if (wallpaperIds.isEmpty()) {
                return Result.Success(Unit)
            }
            wallpaperDao.deleteWallpapersByIds(wallpaperIds)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    override suspend fun deleteWallpapersByFolderId(folderId: String): Result<Unit> {
        return try {
            wallpaperDao.deleteWallpapersByFolder(folderId)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    override suspend fun updateWallpaperOrder(wallpaperId: String, order: Int): Result<Unit> {
        return try {
            wallpaperDao.updateWallpaperOrder(wallpaperId, order)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    override suspend fun validateDirectWallpapers(albumId: String): Result<Int> {
        return try {
            val invalidIds = wallpaperDao.getDirectWallpapersByAlbumSync(albumId)
                .filter { !it.uri.toUri().isValid(contentResolver) }
                .map { it.id }
            invalidIds.chunked(SQL_BATCH_SIZE).forEach { wallpaperDao.deleteWallpapersByIds(it) }
            Result.Success(invalidIds.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    override suspend fun getNextWallpaperInQueue(albumId: String, screenType: ScreenType): Wallpaper? =
        wallpaperQueueDao.getNextWallpaperInQueue(albumId, screenType)?.toDomainModel()

    override suspend fun getAndDequeueWallpaper(albumId: String, screenType: ScreenType): Wallpaper? =
        wallpaperQueueDao.getAndDequeueWallpaper(albumId, screenType)?.toDomainModel()

    override suspend fun removeWallpaperFromQueue(
        albumId: String,
        screenType: ScreenType,
        wallpaperId: String
    ) {
        wallpaperQueueDao.deleteQueueItem(albumId, screenType, wallpaperId)
    }

    override suspend fun restoreWallpaperToQueueFront(
        albumId: String,
        screenType: ScreenType,
        wallpaperId: String
    ) {
        wallpaperQueueDao.restoreQueueItem(albumId, screenType, wallpaperId)
    }

    override suspend fun buildWallpaperQueue(
        albumId: String,
        screenType: ScreenType,
        shuffle: Boolean
    ): Result<Unit> {
        // In shuffle mode, use album-level mutex to ensure synchronized shuffle order
        // In sequential mode, use per-screen mutex for independent rebuilds
        val mutexKey = if (shuffle) albumId else "$albumId:$screenType"
        val mutex = queueRebuildMutexes.getOrPut(mutexKey) { Mutex() }

        return mutex.withLock {
            try {
                // Load only IDs (+ order for sequential mode) instead of full WallpaperEntity
                // objects, to avoid deserializing every field for every wallpaper in the album.
                val otherScreenType: ScreenType? = if (shuffle) when (screenType) {
                    ScreenType.HOME -> ScreenType.LOCK
                    ScreenType.LOCK -> ScreenType.HOME
                    else -> null
                } else null

                val wallpaperIds = if (shuffle) {
                    val ids = wallpaperDao.getWallpaperIdsByAlbum(albumId)

                    // In shuffle mode, sync with the other screen's queue if it exists
                    val otherScreenQueueIds = otherScreenType?.let {
                        try {
                            wallpaperQueueDao.getQueueItems(albumId, it)
                                .map { item -> item.wallpaperId }
                        } catch (_: Exception) { null }
                    }

                    if (otherScreenQueueIds != null && otherScreenQueueIds.isNotEmpty()) {
                        com.anthonyla.paperize.core.util.QueueBuilder.mergeWithExistingQueue(otherScreenQueueIds, ids)
                    } else {
                        com.anthonyla.paperize.core.util.QueueBuilder.buildShuffledQueue(ids)
                    }
                } else {
                    val idsWithOrder = wallpaperDao.getWallpaperIdsAndOrderByAlbum(albumId)
                    com.anthonyla.paperize.core.util.QueueBuilder.buildSequentialQueue(
                        idsWithOrder.map { it.id to it.displayOrder }
                    )
                }

                wallpaperQueueDao.rebuildQueue(albumId, screenType, wallpaperIds)

                // In shuffle mode, pre-populate the other screen's queue with the same order
                // if it doesn't yet exist. This ensures both screens start from the same
                // shuffle sequence, preventing drift when switching to separate schedules.
                if (shuffle && otherScreenType != null) {
                    try {
                        val otherQueueIsEmpty = wallpaperQueueDao
                            .getQueueItems(albumId, otherScreenType).isEmpty()
                        if (otherQueueIsEmpty) {
                            wallpaperQueueDao.rebuildQueue(albumId, otherScreenType, wallpaperIds)
                        }
                    } catch (_: Exception) {
                        // Best-effort: if pre-population fails the other screen will build its
                        // own queue on demand via mergeWithExistingQueue
                    }
                }

                Result.Success(Unit)
            } catch (e: Exception) {
                Result.Error(e)
            }
        }
    }

    override suspend fun clearAllQueues(): Result<Unit> {
        return try {
            wallpaperQueueDao.deleteAllQueueItems()
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    override suspend fun clearQueuesForAlbum(albumId: String): Result<Unit> {
        return try {
            wallpaperQueueDao.clearAllQueues(albumId)
            
            // Prune mutexes for this album to prevent memory leak
            // Keys are either albumId (shuffled) or $albumId:$screenType (sequential)
            // Use exact match or prefix with colon to avoid false positives (e.g., album1 vs album10)
            queueRebuildMutexes.keys.filter { it == albumId || it.startsWith("$albumId:") }.forEach { 
                queueRebuildMutexes.remove(it)
            }
            
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }


    override suspend fun getCurrentWallpaper(albumId: String, screenType: ScreenType): Wallpaper? =
        wallpaperCurrentDao.getCurrentWallpaper(albumId, screenType)?.toDomainModel()

    override fun getCurrentWallpaperFlow(albumId: String, screenType: ScreenType): Flow<Wallpaper?> =
        wallpaperCurrentDao.getCurrentWallpaperFlow(albumId, screenType).map { it?.toDomainModel() }

    override suspend fun setCurrentWallpaper(albumId: String, screenType: ScreenType, wallpaperId: String) {
        wallpaperCurrentDao.upsertCurrentWallpaper(
            WallpaperCurrentEntity(albumId = albumId, screenType = screenType, wallpaperId = wallpaperId)
        )
    }

    override suspend fun syncFolderWallpapers(
        albumId: String,
        folderId: String,
        folderUri: Uri
    ): Result<FolderSyncResult> {
        return try {
            val scan = folderUri.scanFolderTree(context)
            val scannedByUri = scan.images.associateBy { it.uri.toString() }
            val stored = wallpaperDao.getWallpaperUrisByAlbumAndFolder(albumId, folderId).toHashSet()
            val plan = FolderSyncPlanner.plan(stored, scannedByUri.keys, scan.isComplete)

            val now = System.currentTimeMillis()
            val newEntities = plan.toAdd.map { uri ->
                val image = scannedByUri.getValue(uri)
                Wallpaper(
                    id = generateId(),
                    albumId = albumId,
                    folderId = folderId,
                    uri = uri,
                    fileName = image.name,
                    dateModified = image.lastModified,
                    sourceType = WallpaperSourceType.FOLDER,
                    addedAt = now
                ).toEntity()
            }
            newEntities.chunked(SQL_BATCH_SIZE).forEach { wallpaperDao.insertWallpapers(it) }
            plan.toRemove.chunked(SQL_BATCH_SIZE).forEach {
                wallpaperDao.deleteFolderWallpapersByUris(albumId, folderId, it)
            }

            Result.Success(FolderSyncResult(plan.toAdd.size, plan.toRemove.size, scan.isComplete))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    override suspend fun isWallpaperInAlbum(albumId: String, uri: String): Boolean =
        wallpaperDao.getWallpaperByUri(uri)?.let { it.albumId == albumId } ?: false
}

/** Rows per IN (...) / insert batch, well below SQLite's bound-variable limit. */
private const val SQL_BATCH_SIZE = 500
