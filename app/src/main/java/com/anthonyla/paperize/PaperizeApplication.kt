package com.anthonyla.paperize

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.anthonyla.paperize.core.constants.Constants
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.service.widget.PauseResumeWidgetProvider
import com.anthonyla.paperize.core.util.DataResetManager
import com.anthonyla.paperize.service.worker.AlbumRefreshScheduler
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
class PaperizeApplication : Application(), Configuration.Provider, DefaultLifecycleObserver {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var settingsRepository: SettingsRepository

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super<Application>.onCreate()

        // Perform one-time data reset for major version upgrades (e.g., v3 -> v4)
        // Must run before any other initialization that accesses DB/preferences
        DataResetManager.performResetIfNeeded(this)

        // Create notification channel (minSdk is 31, so always supported)
        createNotificationChannel()

        // Process lifecycle distinguishes real background/foreground transitions from activity
        // recreation, so folder-backed albums are refreshed whenever the user returns to the app.
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

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

    override fun onStart(owner: LifecycleOwner) {
        AlbumRefreshScheduler.enqueue(this)
    }
}
