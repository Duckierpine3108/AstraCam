package com.astracam.dualdevice.camera

import android.util.Log
import androidx.camera.core.CameraControl
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

class HdrCaptureCoordinator(
    private val captureController: CameraCaptureController
) {
    private val TAG = "AstraCam_HDRCoord"

    suspend fun captureExposureBracket(
        imageCapture: ImageCapture,
        cameraControl: CameraControl?,
        evStepIndices: List<Int>
    ): List<File> = withContext(Dispatchers.Main) {
        val capturedFiles = mutableListOf<File>()

        for (evIndex in evStepIndices) {
            try {
                if (cameraControl != null) {
                    cameraControl.setExposureCompensationIndex(evIndex)
                    // Allow hardware AE convergence
                    delay(300)
                }

                val deferred = CompletableDeferred<File>()
                captureController.captureStandardPhoto(
                    imageCapture,
                    onSuccess = { file -> deferred.complete(file) },
                    onError = { exc -> deferred.completeExceptionally(exc) }
                )
                val file = deferred.await()
                capturedFiles.add(file)
            } catch (e: Exception) {
                Log.w(TAG, "Failed bracket frame at EV index $evIndex: ${e.message}")
            }
        }

        // Reset AE index to baseline
        cameraControl?.setExposureCompensationIndex(0)
        return@withContext capturedFiles
    }
}