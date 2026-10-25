package com.astracam.dualdevice.gallery

import android.app.AlertDialog
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.astracam.dualdevice.ai.GeminiAiEnhancer
import com.astracam.dualdevice.ai.GeminiEnhanceResult
import com.astracam.dualdevice.databinding.ActivityPhotoReviewBinding
import com.astracam.dualdevice.device.DeviceProfileManager
import com.astracam.dualdevice.processing.DslrImageProcessor
import com.astracam.dualdevice.processing.DslrTuningParameters
import com.astracam.dualdevice.storage.ExifMetadataHandler
import com.astracam.dualdevice.storage.PhotoRepository
import com.astracam.dualdevice.ui.FastBlur
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PhotoReviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhotoReviewBinding
    private var originalFile: File? = null
    private var baseUprightBitmap: Bitmap? = null
    private var currentEnhancedBitmap: Bitmap? = null

    private val dslrProcessor = DslrImageProcessor()
    private val tuningParams = DslrTuningParameters()
    private var renderJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoReviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val path = intent.getStringExtra(EXTRA_PHOTO_PATH)
        if (path == null || !File(path).exists()) {
            Toast.makeText(this, "Image file not found.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        originalFile = File(path)
        val profile = DeviceProfileManager.getProfile(this)
        val repo = PhotoRepository(this)

        try {
            baseUprightBitmap = ExifMetadataHandler.loadOrientedBitmap(
                originalFile!!,
                profile.maxProcessingDimension
            )
            binding.imagePreview.setImageBitmap(baseUprightBitmap)
            generateFrostedGlassBackdrop(baseUprightBitmap!!)
        } catch (e: Exception) {
            Toast.makeText(this, "Decode error: ${e.message}", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        animateUiEntrance()
        triggerRender()

        binding.toggleComparison.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == binding.rbOriginal.id) {
                binding.imagePreview.setImageBitmap(baseUprightBitmap)
            } else {
                binding.imagePreview.setImageBitmap(currentEnhancedBitmap ?: baseUprightBitmap)
            }
        }

        setupSliders()
        setupPresets()

        binding.btnGeminiAi.setOnClickListener {
            val key = GeminiAiEnhancer.getSavedApiKey(this)
            if (key.isNullOrEmpty()) {
                showApiKeyInputDialog()
            } else {
                executeGeminiEnhance()
            }
        }

        binding.btnSave.setOnClickListener {
            currentEnhancedBitmap?.let { bmp ->
                val saved = repo.saveEnhancedBitmap(bmp, originalFile)
                Toast.makeText(this, "Saved to ${saved.name}", Toast.LENGTH_LONG).show()
                finish()
            } ?: Toast.makeText(this, "Rendering in progress...", Toast.LENGTH_SHORT).show()
        }

        binding.btnBack.setOnClickListener { finish() }
    }

    private fun animateUiEntrance() {
        binding.editorContainer.translationY = 300f
        binding.editorContainer.alpha = 0f
        binding.editorContainer.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(450)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun generateFrostedGlassBackdrop(bitmap: Bitmap) {
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val cropY = (bitmap.height * 0.60f).toInt()
                val cropH = (bitmap.height * 0.40f).toInt()
                val bottomSlice = Bitmap.createBitmap(bitmap, 0, cropY, bitmap.width, cropH)

                val scaled = Bitmap.createScaledBitmap(bottomSlice, 160, 100, false)
                val blurred = FastBlur.blur(scaled, 16)

                withContext(Dispatchers.Main) {
                    binding.glassBackdropBlur.setImageBitmap(blurred)
                    binding.glassBackdropBlur.alpha = 0.95f
                }
            } catch (ignored: Exception) {}
        }
    }

    private fun setupSliders() {
        binding.sbShadows.setOnSeekBarChangeListener(object : SimpleSeekBarListener() {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tuningParams.shadows = progress.toFloat()
                    binding.lblShadows.text = "Shadow Recovery: $progress%"
                    debouncedRender()
                }
            }
        })

        binding.sbHighlights.setOnSeekBarChangeListener(object : SimpleSeekBarListener() {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tuningParams.highlights = progress.toFloat()
                    binding.lblHighlights.text = "Highlight Compression: $progress%"
                    debouncedRender()
                }
            }
        })

        binding.sbClarity.setOnSeekBarChangeListener(object : SimpleSeekBarListener() {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tuningParams.clarity = progress.toFloat()
                    binding.lblClarity.text = "Clarity / Micro-Contrast: $progress%"
                    debouncedRender()
                }
            }
        })

        binding.sbWarmth.setOnSeekBarChangeListener(object : SimpleSeekBarListener() {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val shifted = progress - 50
                    tuningParams.temperature = shifted.toFloat()
                    binding.lblWarmth.text = "Color Temperature: ${if (shifted > 0) "+$shifted Warm" else "$shifted Cool"}"
                    debouncedRender()
                }
            }
        })

        binding.sbSharpness.setOnSeekBarChangeListener(object : SimpleSeekBarListener() {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tuningParams.sharpness = progress.toFloat()
                    binding.lblSharpness.text = "Edge Sharpness: $progress%"
                    debouncedRender()
                }
            }
        })
    }

    private fun setupPresets() {
        binding.presetNatural.setOnClickListener {
            applyPreset(shadows = 20f, highlights = 15f, clarity = 15f, temp = 0f, sharpness = 20f)
        }
        binding.presetHdr.setOnClickListener {
            applyPreset(shadows = 50f, highlights = 40f, clarity = 35f, temp = 4f, sharpness = 35f)
        }
        binding.presetVivid.setOnClickListener {
            applyPreset(shadows = 30f, highlights = 25f, clarity = 30f, temp = 8f, sharpness = 40f)
        }
        binding.presetNightClean.setOnClickListener {
            applyPreset(shadows = 45f, highlights = 25f, clarity = 20f, temp = -4f, sharpness = 20f)
        }
    }

    private fun applyPreset(shadows: Float, highlights: Float, clarity: Float, temp: Float, sharpness: Float) {
        tuningParams.shadows = shadows
        tuningParams.highlights = highlights
        tuningParams.clarity = clarity
        tuningParams.temperature = temp
        tuningParams.sharpness = sharpness

        binding.sbShadows.progress = shadows.toInt()
        binding.sbHighlights.progress = highlights.toInt()
        binding.sbClarity.progress = clarity.toInt()
        binding.sbWarmth.progress = (temp + 50).toInt()
        binding.sbSharpness.progress = sharpness.toInt()

        triggerRender()
    }

    private fun debouncedRender() {
        renderJob?.cancel()
        renderJob = lifecycleScope.launch {
            delay(100)
            triggerRender()
        }
    }

    private fun triggerRender() {
        val base = baseUprightBitmap ?: return
        binding.processingIndicator.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val processed = dslrProcessor.processBitmap(base, tuningParams)
                currentEnhancedBitmap = processed
                if (binding.rbEnhanced.isChecked) {
                    binding.imagePreview.setImageBitmap(processed)
                }
            } catch (t: Throwable) {
                Toast.makeText(this@PhotoReviewActivity, "Render error: ${t.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.processingIndicator.visibility = View.GONE
            }
        }
    }

    private fun showApiKeyInputDialog() {
        val input = EditText(this).apply {
            hint = "Paste Google Gemini API Key"
            setPadding(40, 30, 40, 30)
        }
        AlertDialog.Builder(this)
            .setTitle("Enter Gemini API Key")
            .setMessage("Paste your key from Google AI Studio. It will be saved securely on your device.")
            .setView(input)
            .setPositiveButton("Save & Run") { _, _ ->
                val entered = input.text.toString().trim()
                if (entered.isNotEmpty()) {
                    GeminiAiEnhancer.saveApiKey(this, entered)
                    executeGeminiEnhance()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun executeGeminiEnhance() {
        val base = baseUprightBitmap ?: return

        binding.aiScanOverlay.startScanning()
        binding.aiStatusBadge.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                when (val result = GeminiAiEnhancer.analyzeAndOptimize(this@PhotoReviewActivity, base)) {
                    is GeminiEnhanceResult.DirectImage -> {
                        currentEnhancedBitmap = result.bitmap
                        binding.imagePreview.setImageBitmap(result.bitmap)
                        binding.rbEnhanced.isChecked = true
                        Toast.makeText(this@PhotoReviewActivity, "✨ Nano Banana 2 Image Applied!", Toast.LENGTH_SHORT).show()
                    }
                    is GeminiEnhanceResult.TuningRecipe -> {
                        val aiParams = result.params
                        tuningParams.exposure = aiParams.exposure
                        tuningParams.shadows = aiParams.shadows
                        tuningParams.highlights = aiParams.highlights
                        tuningParams.contrast = aiParams.contrast
                        tuningParams.clarity = aiParams.clarity
                        tuningParams.saturation = aiParams.saturation
                        tuningParams.temperature = aiParams.temperature
                        tuningParams.sharpness = aiParams.sharpness

                        binding.sbShadows.progress = aiParams.shadows.toInt()
                        binding.sbHighlights.progress = aiParams.highlights.toInt()
                        binding.sbClarity.progress = aiParams.clarity.toInt()
                        binding.sbWarmth.progress = (aiParams.temperature + 50).toInt()
                        binding.sbSharpness.progress = aiParams.sharpness.toInt()

                        triggerRender()
                        val feedback = result.message ?: "✨ AI Enhancement Applied!"
                        Toast.makeText(this@PhotoReviewActivity, feedback, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                AlertDialog.Builder(this@PhotoReviewActivity)
                    .setTitle("Gemini AI Diagnostic")
                    .setMessage(e.message ?: "Unknown communication error.")
                    .setPositiveButton("Change Key") { _, _ -> showApiKeyInputDialog() }
                    .setNegativeButton("OK", null)
                    .show()
            } finally {
                binding.aiScanOverlay.stopScanning()
                binding.aiStatusBadge.visibility = View.GONE
            }
        }
    }

    companion object {
        const val EXTRA_PHOTO_PATH = "extra_photo_path"
    }

    abstract class SimpleSeekBarListener : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }
}