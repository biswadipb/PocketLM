package com.pocketlm.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.max

/** Keeps a downscaled copy of each image the user sends, so chats can show it later. */
class ImageStore(private val context: Context) {

    private val dir: File
        get() = File(context.filesDir, "images").also { it.mkdirs() }

    /** Decodes [uri], applies its EXIF rotation, shrinks it to [MAX_SIDE] px and saves it as JPEG. */
    suspend fun import(uri: Uri): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw IOException("Cannot open the image")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("That file isn't a readable image")

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IOException("Cannot decode the image")

        val rotation = resolver.openInputStream(uri)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        if (rotation != 0f) {
            val rotated = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation) }, true,
            )
            if (rotated !== bitmap) bitmap.recycle()
            bitmap = rotated
        }

        val longSide = max(bitmap.width, bitmap.height)
        if (longSide > MAX_SIDE) {
            val scale = MAX_SIDE.toFloat() / longSide
            val scaled = Bitmap.createScaledBitmap(
                bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true,
            )
            if (scaled !== bitmap) bitmap.recycle()
            bitmap = scaled
        }

        val file = File(dir, "${UUID.randomUUID()}.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bitmap.recycle()
        file.absolutePath
    }

    suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        File(path).takeIf { it.parentFile == dir }?.delete()
    }

    companion object {
        const val MAX_SIDE = 1024
    }
}
