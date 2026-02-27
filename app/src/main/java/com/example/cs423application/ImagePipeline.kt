package com.example.cs423application

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ImagePipeline {

    /**
     * Stage 1 — Load: decode raw bitmap from URI.
     * Stage 2 — Orientation fix: read EXIF tag, rotate bitmap to match.
     *
     * Runs on Dispatchers.IO so the main thread stays unblocked.
     */
    suspend fun loadAndFixOrientation(context: Context, uri: Uri): Bitmap =
        withContext(Dispatchers.IO) {

            // Stage 1: decode raw pixels (mirrors what Coil does for non-compose use)
            val raw = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: error("Cannot open image stream for $uri")

            // Stage 2: open a second stream to read EXIF without consuming the first
            val degrees = context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                when (
                    exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                ) {
                    ExifInterface.ORIENTATION_ROTATE_90  -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else                                 -> 0f
                }
            } ?: 0f

            if (degrees == 0f) raw
            else Bitmap.createBitmap(
                raw, 0, 0, raw.width, raw.height,
                Matrix().apply { postRotate(degrees) },
                true
            )
        }

    /**
     * Stage 4 — Save copy: write the corrected bitmap as a JPEG
     * into Pictures/CS423/ via MediaStore.
     *
     * No WRITE_EXTERNAL_STORAGE needed on API 29+.
     */
    suspend fun saveCopy(context: Context, bitmap: Bitmap): String =
        withContext(Dispatchers.IO) {

            val name = "cs423_${System.currentTimeMillis()}.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/CS423"
                )
            }

            val insertUri = context.contentResolver
                .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore insert failed")

            context.contentResolver.openOutputStream(insertUri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }

            name
        }
}
