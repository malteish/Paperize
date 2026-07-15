package com.anthonyla.paperize.service.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import com.anthonyla.paperize.R
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.service.worker.WallpaperRescheduler
import com.anthonyla.paperize.service.worker.WallpaperScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Home-screen widget that pauses or resumes the automatic wallpaper changer when tapped.
 *
 * The icon mirrors the current state: a pause glyph while the changer is running, a play
 * glyph while it is paused. Pausing disables the changer and cancels all scheduled
 * WorkManager jobs; resuming re-enables it and rebuilds the schedule from the saved
 * settings via [WallpaperRescheduler] (the same path BootReceiver uses after a reboot).
 *
 * The changer state can also be toggled from inside the app, so widget instances are kept
 * in sync by an observer in PaperizeApplication that watches the persisted setting and
 * requests a refresh via [requestRefresh].
 */
@AndroidEntryPoint
class PauseResumeWidgetProvider : AppWidgetProvider() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var wallpaperScheduler: WallpaperScheduler

    @Inject
    lateinit var wallpaperRescheduler: WallpaperRescheduler

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // onUpdate is dispatched synchronously from onReceive, so goAsync() is still
        // available to keep the receiver alive while the state is read off the main thread.
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val enabled = settingsRepository.getScheduleSettings().enableChanger
                render(context, appWidgetManager, appWidgetIds, enabled)
            } catch (e: Exception) {
                Log.e(TAG, "Error rendering widget", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        // super.onReceive performs Hilt field injection and dispatches the standard
        // app-widget actions (onUpdate, onDeleted, …); call it before touching injected fields.
        super.onReceive(context, intent)

        if (intent.action != ACTION_WIDGET_TAP) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                toggleChanger(context)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling widget tap", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun toggleChanger(context: Context) {
        val enabled = !settingsRepository.getScheduleSettings().enableChanger
        settingsRepository.updateEnableChanger(enabled)

        if (enabled) {
            wallpaperRescheduler.rescheduleFromSettings()
        } else {
            wallpaperScheduler.cancelAllWallpaperChanges()
        }

        // Re-render immediately for instant feedback; the settings observer in
        // PaperizeApplication would also get there, just a broadcast round-trip later.
        val appWidgetManager = AppWidgetManager.getInstance(context)
        render(context, appWidgetManager, widgetIds(context, appWidgetManager), enabled)
    }

    private fun render(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
        changerEnabled: Boolean
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_pause_resume).apply {
            setImageViewResource(
                R.id.widget_icon,
                if (changerEnabled) R.drawable.ic_widget_pause else R.drawable.ic_widget_resume
            )
            setContentDescription(
                R.id.widget_icon,
                context.getString(
                    if (changerEnabled) R.string.pause_wallpaper_changes
                    else R.string.resume_wallpaper_changes
                )
            )
            setOnClickPendingIntent(R.id.widget_root, tapPendingIntent(context))
        }
        appWidgetIds.forEach { appWidgetId ->
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    companion object {
        private const val TAG = "PauseResumeWidget"

        /** Explicit action fired by the widget's click PendingIntent. */
        private const val ACTION_WIDGET_TAP = "com.anthonyla.paperize.WIDGET_TOGGLE_CHANGER"

        /**
         * Ask all placed instances of this widget to re-read the changer state and redraw.
         * No-op when no instance is on the home screen.
         */
        fun requestRefresh(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = widgetIds(context, appWidgetManager)
            if (ids.isEmpty()) return
            val intent = Intent(context, PauseResumeWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intent)
        }

        private fun widgetIds(context: Context, appWidgetManager: AppWidgetManager): IntArray =
            appWidgetManager.getAppWidgetIds(
                ComponentName(context, PauseResumeWidgetProvider::class.java)
            )

        private fun tapPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, PauseResumeWidgetProvider::class.java).apply {
                action = ACTION_WIDGET_TAP
            }
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
    }
}
