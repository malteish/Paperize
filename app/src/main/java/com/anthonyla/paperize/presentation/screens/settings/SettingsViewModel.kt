package com.anthonyla.paperize.presentation.screens.settings
import com.anthonyla.paperize.core.constants.Constants

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.domain.model.AppSettings
import com.anthonyla.paperize.domain.repository.AlbumRepository
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.service.worker.WallpaperScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for Settings screen
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val albumRepository: AlbumRepository,
    private val wallpaperScheduler: WallpaperScheduler
) : ViewModel() {

    companion object {
        private const val TAG = "SettingsViewModel"
    }

    val appSettings: StateFlow<AppSettings?> = settingsRepository.getAppSettingsFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,  // Start loading immediately to prevent onboarding flicker
            initialValue = null
        )

    val wallpaperMode: StateFlow<WallpaperMode> = settingsRepository.getWallpaperModeFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(Constants.FLOW_SUBSCRIPTION_TIMEOUT_MS),
            initialValue = WallpaperMode.STATIC
        )

    /**
     * Human-readable name of the configured premium folder, or null when none is set
     * or the folder is no longer reachable
     */
    val premiumFolderName: StateFlow<String?> = settingsRepository.getAppSettingsFlow()
        .map { it.premiumFolderUri }
        .distinctUntilChanged()
        .map { uri ->
            uri?.let {
                try {
                    DocumentFile.fromTreeUri(context, it.toUri())?.name
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot resolve premium folder name", e)
                    null
                }
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(Constants.FLOW_SUBSCRIPTION_TIMEOUT_MS),
            initialValue = null
        )

    /**
     * Set the folder that "Save to premium" copies wallpapers into
     *
     * @param uri tree URI the caller already holds a persisted permission for
     */
    fun setPremiumFolder(uri: String) {
        viewModelScope.launch {
            settingsRepository.updatePremiumFolderUri(uri)
        }
    }

    /**
     * Forget the configured premium folder
     */
    fun clearPremiumFolder() {
        viewModelScope.launch {
            settingsRepository.updatePremiumFolderUri(null)
        }
    }

    fun updateDarkMode(enabled: Boolean) {
        viewModelScope.launch {
            // Use atomic update to prevent race conditions
            settingsRepository.updateDarkMode(enabled)
        }
    }

    fun updateDynamicTheming(enabled: Boolean) {
        viewModelScope.launch {
            // Use atomic update to prevent race conditions
            settingsRepository.updateDynamicTheming(enabled)
        }
    }

    fun updateAnimate(enabled: Boolean) {
        viewModelScope.launch {
            // Use atomic update to prevent race conditions
            settingsRepository.updateAnimate(enabled)
        }
    }

    fun updateFirstLaunch(isFirstLaunch: Boolean) {
        viewModelScope.launch {
            // Use atomic update to prevent race conditions
            settingsRepository.updateFirstLaunch(isFirstLaunch)
        }
    }

    /**
     * Switch wallpaper mode and reset all data
     * This is required because STATIC and LIVE modes have different capabilities
     * and incompatible wallpaper types
     */
    fun switchWallpaperMode(newMode: WallpaperMode) {
        viewModelScope.launch {
            // Cancel all scheduled wallpaper changes first
            wallpaperScheduler.cancelAllWallpaperChanges()

            // Delete all albums (cascades to delete all wallpapers, folders, and queues)
            when (val result = albumRepository.deleteAllAlbums()) {
                is com.anthonyla.paperize.core.Result.Success -> { /* Success */ }
                is com.anthonyla.paperize.core.Result.Error -> { 
                    Log.e(TAG, "Error deleting albums during mode switch", result.exception)
                }
                is com.anthonyla.paperize.core.Result.Loading -> { /* Loading state not used */ }
            }

            // Reset schedule settings to default (clears effects, intervals, etc.)
            settingsRepository.clearScheduleSettings()

            // Set new wallpaper mode
            settingsRepository.setWallpaperMode(newMode)
        }
    }

    /**
     * Reset all app data - settings, albums, wallpapers, folders, queues, and alarms
     * This completely resets the app to initial state as if just installed
     */
    fun resetAllData() {
        viewModelScope.launch {
            // Cancel all scheduled wallpaper changes first
            wallpaperScheduler.cancelAllWallpaperChanges()

            // Delete all albums (cascades to delete all wallpapers, folders, and queues)
            when (val result = albumRepository.deleteAllAlbums()) {
                is com.anthonyla.paperize.core.Result.Success -> { /* Success */ }
                is com.anthonyla.paperize.core.Result.Error -> { 
                    Log.e(TAG, "Error deleting albums during reset", result.exception)
                }
                is com.anthonyla.paperize.core.Result.Loading -> { /* Loading state not used */ }
            }

            // Clear all settings (DataStore) - resets to default values
            // This includes setting firstLaunch back to true for onboarding
            settingsRepository.clearAllSettings()
        }
    }
}
