package com.astracam.dualdevice.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Range
import android.util.Rational
import android.util.Size
import java.lang.Exception

data class CameraHardwareCapabilities(
    val cameraId: String,
    val lensFacing: Int,
    val isFlashSupported: Boolean,
    val exposureCompensationRange: Range<Int>,
    val exposureCompensationStep: Rational,
    val supportedJpegOutputSizes: List<Size>,
    val isManualSensorSupported: Boolean
)

class CameraCapabilityRepository(private val context: Context) {
    private val cameraManager: CameraManager? =
        context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    fun queryCapabilities(targetFacing: Int): CameraHardwareCapabilities? {
        val cm = cameraManager ?: return null
        try {
            for (id in cm.cameraIdList) {
                val chars = cm.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
                if (facing == targetFacing) {
                    val flashAvailable = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                    val expRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
                    val expStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP) ?: Rational(1, 1)

                    val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()

                    val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                    val isManual = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)

                    return CameraHardwareCapabilities(
                        cameraId = id,
                        lensFacing = facing,
                        isFlashSupported = flashAvailable,
                        exposureCompensationRange = expRange,
                        exposureCompensationStep = expStep,
                        supportedJpegOutputSizes = jpegSizes,
                        isManualSensorSupported = isManual
                    )
                }
            }
        } catch (e: Exception) {
            return null
        }
        return null
    }
}