package com.astracam.dualdevice.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import com.astracam.dualdevice.device.DeviceProfile
import com.astracam.dualdevice.device.DeviceProfileType
import com.astracam.dualdevice.storage.ExifMetadataHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

class ImageProcessingPipeline(private val profile: DeviceProfile) {
    private val TAG = "AstraCam_Pipeline"

    private val toneProcessor = ExposureAndToneProcessor()
    private val noiseProcessor = NoiseReductionProcessor()
    private val detailProcessor = DetailEnhancementProcessor()
    private val colorProcessor = ColourCorrectionProcessor()
    private val hdrMergeProcessor = HdrMergeProcessor()

    suspend fun processSinglePhoto(
        sourceFile: File,
        orientationDegrees: Int = 0
    ): Bitmap = withContext(Dispatchers.Default) {
        Log.i(TAG, "Starting processing pipeline on ${sourceFile.name}")

        // Load properly oriented upright bitmap
        val oriented = ExifMetadataHandler.loadOrientedBitmap(sourceFile, profile.maxProcessingDimension)

        // Step 1: Denoising
        val denoised = if (profile.type == DeviceProfileType.H18_WATCH) {
            oriented
        } else {
            noiseProcessor.applyEdgePreservingDenoise(oriented, spatialSigma = 1.5f, rangeSigma = 20.0f)
        }

        // Step 2: Dynamic range shadow lift & highlight recovery
        val toned = toneProcessor.applyDynamicToneCurve(
            denoised,
            shadowLift = if (profile.type == DeviceProfileType.H18_WATCH) 0.25f else 0.40f,
            highlightCompress = if (profile.type == DeviceProfileType.H18_WATCH) 0.20f else 0.35f
        )

        // Step 3: Color balance & vibrance
        val colored = colorProcessor.applyColorCorrectionAndVibrance(toned, vibranceBoost = 0.20f)

        // Step 4: Fine edge-aware unsharp detail enhancement
        val sharpened = detailProcessor.applyEdgeAwareDetailSharpening(
            colored,
            strength = if (profile.type == DeviceProfileType.H18_WATCH) 0.30f else 0.50f,
            noiseThreshold = 4,
            haloClamp = 35
        )

        return@withContext sharpened
    }

    suspend fun processMultiFrameHdr(bracketFiles: List<File>): Bitmap = withContext(Dispatchers.Default) {
        Log.i(TAG, "Starting GCam-aligned multi-frame HDR merge on ${bracketFiles.size} frames")

        // CRITICAL FIX: Load every bracket frame rotated upright according to its EXIF orientation
        val bitmaps = bracketFiles.map { file ->
            ExifMetadataHandler.loadOrientedBitmap(file, profile.maxProcessingDimension)
        }

        // Merge exposure bracket (with translational alignment and motion deghosting)
        val fused = hdrMergeProcessor.mergeExposureBracket(bitmaps)

        // Refine with local color and edge contrast
        val colored = colorProcessor.applyColorCorrectionAndVibrance(fused, vibranceBoost = 0.15f)
        val sharpened = detailProcessor.applyEdgeAwareDetailSharpening(colored, strength = 0.35f)

        return@withContext sharpened
    }
}