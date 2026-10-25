package com.astracam.dualdevice.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.lang.Exception

class CameraManagerController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    private val TAG = "AstraCam_Manager"
    private var cameraProvider: ProcessCameraProvider? = null
    private var activeCamera: Camera? = null
    var currentLensFacing: Int = CameraSelector.LENS_FACING_BACK
        private set

    fun initialize(onReady: () -> Unit, onError: (Throwable) -> Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                onReady()
            } catch (t: Throwable) {
                Log.e(TAG, "ProcessCameraProvider failed initialization", t)
                onError(t)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun bindCamera(
        previewView: PreviewView,
        imageCapture: ImageCapture,
        lensFacing: Int = currentLensFacing
    ): Boolean {
        val provider = cameraProvider ?: return false
        try {
            provider.unbindAll()

            val selector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            if (!provider.hasCamera(selector)) {
                Log.w(TAG, "Selected camera facing $lensFacing not available.")
                return false
            }

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            activeCamera = provider.bindToLifecycle(
                lifecycleOwner,
                selector,
                preview,
                imageCapture
            )

            currentLensFacing = lensFacing
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed binding CameraX use cases", e)
            return false
        }
    }

    fun toggleCamera(previewView: PreviewView, imageCapture: ImageCapture): Boolean {
        val provider = cameraProvider ?: return false
        val newFacing = if (currentLensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }

        val selector = CameraSelector.Builder().requireLensFacing(newFacing).build()
        if (provider.hasCamera(selector)) {
            return bindCamera(previewView, imageCapture, newFacing)
        }
        return false
    }

    fun setFlashMode(imageCapture: ImageCapture, mode: Int) {
        try {
            imageCapture.flashMode = mode
        } catch (e: Exception) {
            Log.e(TAG, "Unable to set flash mode: $mode", e)
        }
    }

    fun setExposureCompensation(index: Int) {
        activeCamera?.cameraControl?.setExposureCompensationIndex(index)
    }

    fun getCameraControl(): CameraControl? = activeCamera?.cameraControl
    fun getCameraInfo(): CameraInfo? = activeCamera?.cameraInfo
}