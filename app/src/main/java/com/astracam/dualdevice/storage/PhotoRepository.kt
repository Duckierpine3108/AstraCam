package com.astracam.dualdevice.storage

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PhotoRepository(private val context: Context) {
    private val TAG = "AstraCam_Repo"

    fun createTemporaryCaptureFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val cacheDir = context.cacheDir
        return File.createTempFile("ASTRACAM_RAW_${timeStamp}_", ".jpg", cacheDir)
    }

    fun saveOriginalPhoto(tempFile: File): File {
        val originalsDir = getAppAlbumDirectory("Originals")
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val destinationFile = File(originalsDir, "ASTRA_ORIG_${timeStamp}.jpg")

        tempFile.copyTo(destinationFile, overwrite = true)
        indexMediaFile(destinationFile, "Pictures/AstraCam/Originals")
        return destinationFile
    }

    fun saveEnhancedBitmap(bitmap: Bitmap, sourceFile: File? = null): File {
        val enhancedDir = getAppAlbumDirectory("Enhanced")
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val destinationFile = File(enhancedDir, "ASTRA_ENHANCED_${timeStamp}.jpg")

        FileOutputStream(destinationFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }

        sourceFile?.let { src ->
            ExifMetadataHandler.copyExifData(src, destinationFile, "AstraCam HDR Detail Engine")
        }

        indexMediaFile(destinationFile, "Pictures/AstraCam/Enhanced")
        return destinationFile
    }

    private fun getAppAlbumDirectory(subFolder: String): File {
        val root = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "AstraCam/$subFolder")
        } else {
            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            File(picturesDir, "AstraCam/$subFolder")
        }
        if (!root.exists()) root.mkdirs()
        return root
    }

    private fun indexMediaFile(file: File, relativeSubPath: String) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, relativeSubPath)
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                } else {
                    put(MediaStore.Images.Media.DATA, file.absolutePath)
                }
            }
            context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to index image into MediaStore: ${e.message}")
        }
    }
}