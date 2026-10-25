package com.astracam.dualdevice.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import com.astracam.dualdevice.storage.PhotoRepository
import java.io.File

class CameraCaptureController(
    private val context: Context,
    private val photoRepository: PhotoRepository
) {
    private val TAG = "AstraCam_Capture"

    fun buildImageCapture(targetRotation: Int): ImageCapture {
        return ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setTargetRotation(targetRotation)
            .build()
    }

    fun captureStandardPhoto(
        imageCapture: ImageCapture,
        onSuccess: (File) -> Unit,
        onError: (ImageCaptureException) -> Unit
    ) {
        val tempFile = photoRepository.createTemporaryCaptureFile()
        val outputOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    Log.i(TAG, "Photo captured successfully: ${tempFile.absolutePath}")
                    onSuccess(tempFile)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                    onError(exception)
                }
            }
        )
    }
}