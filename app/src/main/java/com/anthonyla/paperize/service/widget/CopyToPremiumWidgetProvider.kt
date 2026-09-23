package com.anthonyla.paperize.service.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import android.widget.Toast
import com.anthonyla.paperize.R
import com.anthonyla.paperize.domain.usecase.CopyToPremiumFolderResult
import com.anthonyla.paperize.domain.usecase.CopyToPremiumFolderUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Home-screen widget that copies the wallpaper currently applied by Paperize into the
 * user's premium folder when tapped.
 *
 * The premium folder is a Storage Access Framework tree picked in Settings; until one is
 * picked, a tap only tells the user to set it up. Every tap reports what happened as a
 * toast, since a copy is otherwise invisible from the home screen.
 */
@AndroidEntryPoint
class CopyToPremiumWidgetProvider : AppWidgetProvider() {

    @Inject
    lateinit var copyToPremiumFolderUseCase: CopyToPremiumFolderUseCase

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { appWidgetId ->
            val views = RemoteViews(context.packageName, R.layout.widget_copy_to_premium).apply {
                setOnClickPendingIntent(R.id.widget_root, tapPendingIntent(context))
            }
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        // super.onReceive performs Hilt field injection and dispatches the standard
        // app-widget actions (onUpdate, onDeleted, …); call it before touching injected fields.
        super.onReceive(context, intent)

        if (intent.action != ACTION_WIDGET_TAP) return

        // goAsync() keeps the receiver alive while the image is copied off the main thread.
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val result = copyToPremiumFolderUseCase()
                withContext(Dispatchers.Main) { showResult(context, result) }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling widget tap", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun showResult(context: Context, result: CopyToPremiumFolderResult) {
        val message = when (result) {
            is CopyToPremiumFolderResult.Copied ->
                context.getString(R.string.copied_to_premium_folder, result.name)
            is CopyToPremiumFolderResult.AlreadyPresent ->
                context.getString(R.string.already_in_premium_folder, result.name)
            CopyToPremiumFolderResult.NoFolderConfigured ->
                context.getString(R.string.no_premium_folder_configured)
            CopyToPremiumFolderResult.FolderUnavailable ->
                context.getString(R.string.premium_folder_unavailable)
            CopyToPremiumFolderResult.NoCurrentWallpaper ->
                context.getString(R.string.no_current_wallpaper_to_copy)
            is CopyToPremiumFolderResult.Failed ->
                context.getString(R.string.copy_to_premium_folder_failed)
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "CopyToPremiumWidget"

        /** Explicit action fired by the widget's click PendingIntent. */
        private const val ACTION_WIDGET_TAP = "com.anthonyla.paperize.WIDGET_COPY_TO_PREMIUM"

        /** Also used by the combined widgets, so a tap there runs this provider's handler. */
        internal fun tapPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, CopyToPremiumWidgetProvider::class.java).apply {
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
