package com.astracam.dualdevice.device

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build

object DeviceDiagnostics {

    fun generateReport(context: Context, activeProfile: DeviceProfile): String {
        val sb = StringBuilder()
        sb.append("=== ASTRACAM SYSTEM DIAGNOSTICS ===\n\n")

        sb.append("[Device Identity]\n")
        sb.append("Brand: ").append(Build.BRAND).append("\n")
        sb.append("Manufacturer: ").append(Build.MANUFACTURER).append("\n")
        sb.append("Model: ").append(Build.MODEL).append("\n")
        sb.append("Device: ").append(Build.DEVICE).append("\n")
        sb.append("Product: ").append(Build.PRODUCT).append("\n")
        sb.append("Fingerprint: ").append(Build.FINGERPRINT).append("\n")
        sb.append("Android API Level: ").append(Build.VERSION.SDK_INT).append("\n")
        sb.append("Android Release: ").append(Build.VERSION.RELEASE).append("\n\n")

        sb.append("[Active Profile]\n")
        sb.append("Profile Type: ").append(activeProfile.type.name).append("\n")
        sb.append("Round Screen: ").append(activeProfile.isRoundScreen).append("\n")
        sb.append("Processing Dimension Limit: ").append(activeProfile.maxProcessingDimension).append(" px\n")
        sb.append("Multi-Frame HDR Allowed: ").append(activeProfile.enableMultiFrameHdr).append("\n")
        sb.append("Memory Budget: ").append(activeProfile.memoryBudgetMb).append(" MB\n\n")

        sb.append("[Camera2 Hardware Enumeration]\n")
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (cm == null) {
            sb.append("Error: CameraManager not available on this platform.\n")
            return sb.toString()
        }

        try {
            val cameraIds = cm.cameraIdList
            sb.append("Available Physical/Logical Camera Count: ").append(cameraIds.size).append("\n")

            for (id in cameraIds) {
                sb.append("\n- Camera ID [").append(id).append("]:\n")
                val chars = cm.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                val facingStr = when (facing) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                    CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
                    else -> "UNKNOWN"
                }
                sb.append("  Facing: ").append(facingStr).append("\n")

                val hwLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                val hwLevelStr = when (hwLevel) {
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                    else -> "UNKNOWN"
                }
                sb.append("  Hardware Level: ").append(hwLevelStr).append("\n")

                val flashAvailable = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                sb.append("  Flash Available: ").append(flashAvailable).append("\n")

                val expRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                sb.append("  Exposure Range: ").append(expRange?.toString() ?: "None").append("\n")

                val expStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
                sb.append("  Exposure Step: ").append(expStep?.toString() ?: "None").append("\n")
            }
        } catch (e: Exception) {
            sb.append("Exception enumerating cameras: ").append(e.message).append("\n")
        }

        return sb.toString()
    }
}