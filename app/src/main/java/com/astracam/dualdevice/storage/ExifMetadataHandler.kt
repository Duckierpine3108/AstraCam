package com.astracam.dualdevice.storage

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.lang.Exception

object ExifMetadataHandler {

    fun getExifOrientationDegrees(file: File): Int {
        return try {
            val exif = ExifInterface(file.absolutePath)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: Exception) {
            0
        }
    }

    fun copyExifData(source: File, destination: File, customSoftwareTag: String = "AstraCam Engine") {
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
                if (value != null) {
                    dstExif.setAttribute(tag, value)
                }
            }

            dstExif.setAttribute(ExifInterface.TAG_SOFTWARE, customSoftwareTag)
            dstExif.saveAttributes()
        } catch (ignored: Exception) {}
    }
}