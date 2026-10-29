package com.best.player

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.SurfaceView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Grabs the current video frame and saves it to Pictures/BestPlayer.
 *
 * The primary path decodes the exact frame from the file at the video's native
 * resolution (sharper than a screenshot). If the decoder can't do that, it falls
 * back to copying the pixels currently shown on the SurfaceView.
 */
object FrameCapture {

    const val ALBUM = "BestPlayer"

    suspend fun decodeFrame(context: Context, uri: Uri, timeUs: Long): Bitmap? =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            } catch (e: Exception) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        }

    suspend fun copySurface(surfaceView: SurfaceView): Bitmap? {
        val w = surfaceView.width
        val h = surfaceView.height
        if (w <= 0 || h <= 0 || !surfaceView.holder.surface.isValid) return null
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        return suspendCancellableCoroutine { cont ->
            try {
                PixelCopy.request(surfaceView, bitmap, { result ->
                    cont.resume(if (result == PixelCopy.SUCCESS) bitmap else null)
                }, Handler(Looper.getMainLooper()))
            } catch (e: IllegalArgumentException) {
                cont.resume(null)
            }
        }
    }

    /** Crops [bitmap] to [fraction] (0..1 on each axis). Returns the original if it covers it all. */
    fun crop(bitmap: Bitmap, fraction: RectF): Bitmap {
        val x = (fraction.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val y = (fraction.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val w = ((fraction.right * bitmap.width).toInt() - x).coerceIn(1, bitmap.width - x)
        val h = ((fraction.bottom * bitmap.height).toInt() - y).coerceIn(1, bitmap.height - y)
        if (x == 0 && y == 0 && w == bitmap.width && h == bitmap.height) return bitmap
        return Bitmap.createBitmap(bitmap, x, y, w, h)
    }

    /** Saves as lossless PNG. Returns the saved file name, or null on failure. */
    suspend fun save(context: Context, bitmap: Bitmap, baseName: String): String? =
        withContext(Dispatchers.IO) {
            val fileName = "$baseName.png"
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val uri = runCatching { resolver.insert(collection, values) }.getOrNull()
                ?: return@withContext null
            try {
                val ok = resolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                } ?: false
                if (!ok) {
                    resolver.delete(uri, null, null)
                    return@withContext null
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                fileName
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                null
            }
        }
}
