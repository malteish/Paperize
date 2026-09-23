package com.anthonyla.paperize.core.util

import android.app.WallpaperManager
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.anthonyla.paperize.core.constants.Constants
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

private const val TAG = "WallpaperManagerExt"

/**
 * Apply a bitmap and treat WallpaperManager's documented zero return value as a failure.
 *
 * The bitmap is not handed to [WallpaperManager.setBitmap] directly: setBitmap() PNG-encodes
 * the whole bitmap on the calling thread before streaming it to the system, and for a
 * parallax-sized canvas that encode alone takes seconds. Encoding to lossless WebP at low
 * effort and passing the bytes to [WallpaperManager.setStream] gives pixel-identical output
 * several times faster. (JPEG would be faster still, but its chroma subsampling visibly dulls
 * vivid photos.) If the encode or the stream call throws, setBitmap() is used instead.
 */
fun WallpaperManager.setBitmapChecked(bitmap: Bitmap, which: Int): Int {
    check(bitmap.width > 0 && bitmap.height > 0) {
        "Invalid bitmap dimensions: ${bitmap.width}x${bitmap.height}"
    }
    check(!bitmap.isRecycled) { "Bitmap has been recycled" }

    val wallpaperId = try {
        setWebpStream(bitmap, which)
    } catch (e: Exception) {
        Log.w(TAG, "setStream path failed, falling back to setBitmap", e)
        setBitmap(bitmap, null, true, which)
    }
    requireWallpaperSetSucceeded(wallpaperId)
    return wallpaperId
}

private fun WallpaperManager.setWebpStream(bitmap: Bitmap, which: Int): Int {
    val start = SystemClock.elapsedRealtime()
    val bytes = ByteArrayOutputStream(bitmap.byteCount / 8).also { buffer ->
        if (!bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, Constants.WALLPAPER_WEBP_EFFORT, buffer)) {
            throw IllegalStateException("WebP encode failed")
        }
    }.toByteArray()
    val encoded = SystemClock.elapsedRealtime()
    val wallpaperId = setStream(ByteArrayInputStream(bytes), null, true, which)
    Log.d(
        TAG,
        "Wallpaper set (which=$which, ${bitmap.width}x${bitmap.height}): " +
            "webp ${encoded - start}ms (${bytes.size / 1024}KB), " +
            "setStream ${SystemClock.elapsedRealtime() - encoded}ms"
    )
    return wallpaperId
}

internal fun requireWallpaperSetSucceeded(wallpaperId: Int) {
    if (wallpaperId == 0) {
        throw IOException("WallpaperManager rejected the wallpaper")
    }
}
