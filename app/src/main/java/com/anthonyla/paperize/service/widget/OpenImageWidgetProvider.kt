package com.anthonyla.paperize.service.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.anthonyla.paperize.R
import com.anthonyla.paperize.core.util.openWallpaperInGallery
import com.anthonyla.paperize.domain.usecase.GetCurrentWallpaperUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Home-screen widget that opens the current wallpaper's source image in a gallery app,
 * like the "Open current wallpaper in gallery" button on the home screen.
 *
 * The tap starts [OpenCurrentWallpaperActivity] directly instead of broadcasting to this
 * provider: a broadcast receiver may not start activities from the background on Android
 * 10+, while an activity PendingIntent fired by a widget tap may.
 */
class OpenImageWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { appWidgetId ->
            val views = RemoteViews(context.packageName, R.layout.widget_open_image).apply {
                setOnClickPendingIntent(R.id.widget_root, tapPendingIntent(context))
            }
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    companion object {
        /** Also used by the combined widgets. */
        internal fun tapPendingIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, OpenCurrentWallpaperActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
    }
}

/**
 * Invisible trampoline: looks up the current wallpaper, hands its image to a viewer app and
 * finishes. It runs as an activity so it may start the viewer and grant it read access to
 * the image URI. A translucent theme, not Theme.NoDisplay, because the lookup is a database
 * read that finishes after onResume, and Theme.NoDisplay activities must finish before it.
 */
@AndroidEntryPoint
class OpenCurrentWallpaperActivity : ComponentActivity() {

    @Inject
    lateinit var getCurrentWallpaperUseCase: GetCurrentWallpaperUseCase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            try {
                openWallpaperInGallery(getCurrentWallpaperUseCase())
            } finally {
                finish()
            }
        }
    }
}
