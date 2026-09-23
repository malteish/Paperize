package com.anthonyla.paperize.service.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import com.anthonyla.paperize.R
import com.anthonyla.paperize.service.wallpaper.WallpaperChangeService

/**
 * Home-screen widget that changes the wallpaper immediately when tapped.
 *
 * Mirrors [com.anthonyla.paperize.service.tile.WallpaperTileService]: a tap starts
 * [WallpaperChangeService] with [WallpaperChangeService.ACTION_CHANGE_WALLPAPER_AUTO], which
 * picks the static or live path from the saved wallpaper mode and restarts the automatic
 * countdown after the manual change.
 *
 * Starting a foreground service from here is allowed even while the app is backgrounded
 * because a user tapping an app widget is an exemption to the Android 12+ restriction on
 * starting foreground services from the background.
 */
class ChangeWallpaperWidgetProvider : AppWidgetProvider() {

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
        super.onReceive(context, intent)

        if (intent.action != ACTION_WIDGET_TAP) return

        try {
            context.startForegroundService(
                Intent(context, WallpaperChangeService::class.java).apply {
                    action = WallpaperChangeService.ACTION_CHANGE_WALLPAPER_AUTO
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error handling widget tap", e)
        }
    }

    companion object {
        private const val TAG = "ChangeWallpaperWidget"

        /** Explicit action fired by the widget's click PendingIntent. */
        private const val ACTION_WIDGET_TAP = "com.anthonyla.paperize.WIDGET_CHANGE_WALLPAPER"

        /** Also used by the combined widgets, so a tap there runs this provider's handler. */
        internal fun changePendingIntent(context: Context): PendingIntent {
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
