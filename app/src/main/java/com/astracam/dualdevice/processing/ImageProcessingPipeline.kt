package com.astracam.dualdevice.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import com.astracam.dualdevice.device.DeviceProfile
import com.astracam.dualdevice.device.DeviceProfileType
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

        // Bounded decode based on target profile memory budget
        val decoded = decodeDownscaled(sourceFile.absolutePath, profile.maxProcessingDimension)
        val oriented = if (orientationDegrees != 0) {
            rotateBitmap(decoded, orientationDegrees)
        } else decoded

        // Step 1: Noise reduction (lighter parameters on watch to respect CPU)
        val denoised = if (profile.type == DeviceProfileType.H18_WATCH) {
            oriented // Skip heavy spatial convolution on watch
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

    suspend fun processMultiFrameHdr(
        bracketFiles: List<File>,
        orientationDegrees: Int = 0
    ): Bitmap = withContext(Dispatchers.Default) {
        val bitmaps = bracketFiles.map { file ->
            val decoded = decodeDownscaled(file.absolutePath, profile.maxProcessingDimension)
            if (orientationDegrees != 0) rotateBitmap(decoded, orientationDegrees) else decoded
        }

        // Merge exposure bracket
        val fused = hdrMergeProcessor.mergeExposureBracket(bitmaps)

        // Refine with local color and edge contrast
        val colored = colorProcessor.applyColorCorrectionAndVibrance(fused, vibranceBoost = 0.15f)
        val sharpened = detailProcessor.applyEdgeAwareDetailSharpening(colored, strength = 0.35f)

        return@withContext sharpened
    }

    private fun decodeDownscaled(path: String, maxDim: Int): Bitmap {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)

        var sampleSize = 1
        val maxOriginal = max(options.outWidth, options.outHeight)
        while ((maxOriginal / sampleSize) > maxDim) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(path, decodeOptions)
    }

    private fun rotateBitmap(src: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }
}