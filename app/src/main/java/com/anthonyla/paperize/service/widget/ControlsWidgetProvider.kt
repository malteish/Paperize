package com.anthonyla.paperize.service.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import androidx.annotation.LayoutRes
import com.anthonyla.paperize.R
import com.anthonyla.paperize.domain.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Home-screen widget holding all three widget buttons: change wallpaper, pause/resume and
 * copy to premium folder.
 *
 * This provider only draws the buttons. Each button fires the same PendingIntent as its
 * single-button widget, so taps are handled by [ChangeWallpaperWidgetProvider],
 * [PauseResumeWidgetProvider] and [CopyToPremiumWidgetProvider], and behave identically.
 * The pause/resume icon follows the changer state: [PauseResumeWidgetProvider.requestRefresh]
 * and its tap handler call [requestRefreshAll].
 *
 * Two subclasses exist because a widget provider has exactly one size and layout:
 * [ControlsRowWidgetProvider] (2x1) and [ControlsColumnWidgetProvider] (1x2).
 */
abstract class ControlsWidgetProvider : AppWidgetProvider() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @get:LayoutRes
    protected abstract val layoutRes: Int

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
                val views = buildViews(context, enabled)
                appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, views) }
            } catch (e: Exception) {
                Log.e(TAG, "Error rendering widget", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun buildViews(context: Context, changerEnabled: Boolean) =
        RemoteViews(context.packageName, layoutRes).apply {
            setImageViewResource(
                R.id.widget_pause_resume,
                if (changerEnabled) R.drawable.ic_widget_pause else R.drawable.ic_widget_resume
            )
            setContentDescription(
                R.id.widget_pause_resume,
                context.getString(
                    if (changerEnabled) R.string.pause_wallpaper_changes
                    else R.string.resume_wallpaper_changes
                )
            )
            setOnClickPendingIntent(
                R.id.widget_change,
                ChangeWallpaperWidgetProvider.changePendingIntent(context)
            )
            setOnClickPendingIntent(
                R.id.widget_pause_resume,
                PauseResumeWidgetProvider.tapPendingIntent(context)
            )
            setOnClickPendingIntent(
                R.id.widget_premium,
                CopyToPremiumWidgetProvider.tapPendingIntent(context)
            )
        }

    companion object {
        private const val TAG = "ControlsWidget"

        private val providers = listOf(
            ControlsRowWidgetProvider::class.java,
            ControlsColumnWidgetProvider::class.java
        )

        /**
         * Ask all placed combined widgets to re-read the changer state and redraw.
         * No-op for a size with no instance on the home screen.
         */
        fun requestRefreshAll(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            providers.forEach { provider ->
                val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, provider))
                if (ids.isEmpty()) return@forEach
                val intent = Intent(context, provider).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
                context.sendBroadcast(intent)
            }
        }
    }
}

/** The three widget buttons side by side, 2x1. */
@AndroidEntryPoint
class ControlsRowWidgetProvider : ControlsWidgetProvider() {
    override val layoutRes = R.layout.widget_controls_row
}

/** The three widget buttons stacked, 1x2. */
@AndroidEntryPoint
class ControlsColumnWidgetProvider : ControlsWidgetProvider() {
    override val layoutRes = R.layout.widget_controls_column
}
