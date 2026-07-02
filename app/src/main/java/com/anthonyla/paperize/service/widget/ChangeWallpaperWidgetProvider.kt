package com.anthonyla.paperize.service.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import com.anthonyla.paperize.R
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.core.constants.Constants
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.service.wallpaper.WallpaperChangeService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Home-screen widget that changes the wallpaper immediately when tapped.
 *
 * Mirrors [com.anthonyla.paperize.service.tile.WallpaperTileService]: a tap reads the
 * current wallpaper mode and either asks the live wallpaper to reload or starts
 * [WallpaperChangeService] to advance the static wallpaper queue for BOTH screens.
 *
 * Starting a foreground service from here is allowed even while the app is backgrounded
 * because a user tapping an app widget is an exemption to the Android 12+ restriction on
 * starting foreground services from the background.
 */
@AndroidEntryPoint
class ChangeWallpaperWidgetProvider : AppWidgetProvider() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { appWidgetId ->
            val views = RemoteViews(context.packageName, R.layout.widget_change_wallpaper).apply {
                setOnClickPendingIntent(R.id.widget_root, changePendingIntent(context))
            }
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        // super.onReceive performs Hilt field injection and dispatches the standard
        // app-widget actions (onUpdate, onDeleted, …); call it before touching injected fields.
        super.onReceive(context, intent)

        if (intent.action != ACTION_WIDGET_TAP) return

        // goAsync() keeps the receiver alive while settings are read and the change is
        // dispatched off the main thread.
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                triggerWallpaperChange(context)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling widget tap", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun triggerWallpaperChange(context: Context) {
        if (settingsRepository.getWallpaperMode() == WallpaperMode.LIVE) {
            // Live mode: ask the running live wallpaper service to reload.
            context.sendBroadcast(
                Intent(Constants.ACTION_RELOAD_WALLPAPER).setPackage(context.packageName)
            )
        } else {
            // Static mode: advance the queue for BOTH screens via the foreground service.
            val serviceIntent = Intent(context, WallpaperChangeService::class.java).apply {
                action = Constants.ACTION_CHANGE_WALLPAPER
                putExtra(Constants.EXTRA_SCREEN_TYPE, ScreenType.BOTH.name)
            }
            context.startForegroundService(serviceIntent)
        }
    }

    companion object {
        private const val TAG = "ChangeWallpaperWidget"

        /** Explicit action fired by the widget's click PendingIntent. */
        private const val ACTION_WIDGET_TAP = "com.anthonyla.paperize.WIDGET_CHANGE_WALLPAPER"

        private fun changePendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, ChangeWallpaperWidgetProvider::class.java).apply {
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
