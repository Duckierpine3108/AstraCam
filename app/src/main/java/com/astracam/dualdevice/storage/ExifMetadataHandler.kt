package com.astracam.dualdevice.storage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.max

object ExifMetadataHandler {

    /**
     * Reads EXIF orientation, decodes bounded bitmap, and physically rotates pixels
     * so the bitmap is 100% upright.
     */
    fun loadOrientedBitmap(file: File, maxDim: Int): Bitmap {
        val exif = ExifInterface(file.absolutePath)
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )

        val rotationDegrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }

        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)

        var sampleSize = 1
        val maxOriginal = max(options.outWidth, options.outHeight)
        while ((maxOriginal / sampleSize) > maxDim) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val decoded = BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
            ?: throw IllegalStateException("Failed to decode: ${file.absolutePath}")

        return if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (rotated != decoded) decoded.recycle()
            rotated
        } else {
            decoded
        }
    }

    /**
     * Copies camera capture tags to the enhanced file, but FORCES orientation
     * to NORMAL (1) to prevent double-rotation by external viewers.
     */
    fun copyExifData(source: File, destination: File, customSoftwareTag: String = "AstraCam DSLR Engine") {
        try {
            val srcExif = ExifInterface(source.absolutePath)
            val dstExif = ExifInterface(destination.absolutePath)

            val tags = arrayOf(
                ExifInterface.TAG_DATETIME,
                ExifInterface.TAG_DATETIME_ORIGINAL,
                ExifInterface.TAG_DATETIME_DIGITIZED,
                ExifInterface.TAG_MAKE,
                ExifInterface.TAG_MODEL,
                ExifInterface.TAG_FLASH,
                ExifInterface.TAG_WHITE_BALANCE,
                ExifInterface.TAG_EXPOSURE_TIME,
                ExifInterface.TAG_F_NUMBER,
                ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY
            )

            for (tag in tags) {
                val value = srcExif.getAttribute(tag)
                if (value != null) dstExif.setAttribute(tag, value)
            }

            // CRITICAL: Since pixels are already rotated upright, set orientation to NORMAL (1)
            dstExif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            dstExif.setAttribute(ExifInterface.TAG_SOFTWARE, customSoftwareTag)
            dstExif.saveAttributes()
        } catch (ignored: Exception) {}
    }
}