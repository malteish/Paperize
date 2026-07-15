package com.anthonyla.paperize

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.anthonyla.paperize.core.constants.Constants
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.service.widget.PauseResumeWidgetProvider
import com.anthonyla.paperize.service.worker.AlbumRefreshWorker
import com.anthonyla.paperize.core.util.DataResetManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Application class for Paperize
 *
 * Annotated with @HiltAndroidApp to enable dependency injection
 * Implements Configuration.Provider for WorkManager with Hilt support
 */
@HiltAndroidApp
class PaperizeApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var settingsRepository: SettingsRepository

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Perform one-time data reset for major version upgrades (e.g., v3 -> v4)
        // Must run before any other initialization that accesses DB/preferences
        DataResetManager.performResetIfNeeded(this)

        // Create notification channel (minSdk is 31, so always supported)
        createNotificationChannel()

        // Trigger album refresh on app cold start to validate and update all albums
        refreshAlbumsOnStartup()

        // Keep pause/resume widgets showing the real changer state
        observeChangerStateForWidgets()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    /**
     * The changer can be toggled from many places (settings UI, widget taps, workers that
     * disable it when albums disappear), so instead of refreshing the widget from every
     * caller, watch the single persisted setting. Any process start re-syncs stale widgets
     * for free, and the refresh is a no-op while no widget is placed.
     */
    private fun observeChangerStateForWidgets() {
        applicationScope.launch {
            settingsRepository.getScheduleSettingsFlow()
                .map { it.enableChanger }
                .distinctUntilChanged()
                .collect { PauseResumeWidgetProvider.requestRefresh(this@PaperizeApplication) }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            Constants.NOTIFICATION_CHANNEL_ID,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Refresh all albums on app startup to validate and update wallpapers/folders
     *
     * This runs in the background without blocking app startup and ensures:
     * - Invalid wallpapers/folders are removed
     * - New wallpapers are discovered in existing folders
     * - Album covers are up-to-date
     */
    private fun refreshAlbumsOnStartup() {
        val workManager = WorkManager.getInstance(this)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()

        val refreshWorkRequest = OneTimeWorkRequestBuilder<AlbumRefreshWorker>()
            .setConstraints(constraints)
            .addTag("startup_refresh")
            .build()

        // Use KEEP policy to avoid duplicate refreshes if app is quickly reopened
        workManager.enqueueUniqueWork(
            "album_refresh_on_startup",
            ExistingWorkPolicy.KEEP,
            refreshWorkRequest
        )
    }
}
