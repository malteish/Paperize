package com.anthonyla.paperize.core.util

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.net.toUri
import com.anthonyla.paperize.R
import com.anthonyla.paperize.domain.model.Wallpaper

private const val TAG = "ImageViewerUtil"

/**
 * Open the source image of [wallpaper] in the user's gallery app, or tell the user why not.
 *
 * Pass null when no current wallpaper was found; that case is reported as a toast too, so
 * every caller (in-app button, open-image widget) gives the same feedback. Toasts use the
 * application context so they outlive a trampoline activity that finishes right after.
 */
fun Context.openWallpaperInGallery(wallpaper: Wallpaper?) {
    if (wallpaper == null) {
        Toast.makeText(applicationContext, R.string.no_current_wallpaper_to_open, Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(wallpaper.uri.toUri(), "image/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to open current wallpaper in gallery", e)
        Toast.makeText(applicationContext, R.string.no_app_to_open_image, Toast.LENGTH_SHORT).show()
    }
}
