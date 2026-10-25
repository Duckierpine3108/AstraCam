package com.astracam.dualdevice.gallery

import android.app.AlertDialog
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.astracam.dualdevice.R
import com.astracam.dualdevice.ai.GeminiAiEnhancer
import com.astracam.dualdevice.databinding.ActivityPhotoReviewBinding
import com.astracam.dualdevice.device.DeviceProfileManager
import com.astracam.dualdevice.processing.DslrImageProcessor
import com.astracam.dualdevice.processing.DslrTuningParameters
import com.astracam.dualdevice.storage.ExifMetadataHandler
import com.astracam.dualdevice.storage.PhotoRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

        // Load properly oriented bitmap
        try {
            baseUprightBitmap = ExifMetadataHandler.loadOrientedBitmap(
                originalFile!!,
                profile.maxProcessingDimension
            )
            binding.imagePreview.setImageBitmap(baseUprightBitmap)
        } catch (e: Exception) {
            Toast.makeText(this, "Decode error: ${e.message}", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Apply default DSLR processing pass
        triggerRender()

        // Toggle Original vs Enhanced
        binding.toggleComparison.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == binding.rbOriginal.id) {
                binding.imagePreview.setImageBitmap(baseUprightBitmap)
            } else {
                binding.imagePreview.setImageBitmap(currentEnhancedBitmap ?: baseUprightBitmap)
            }
        }

        // Manual Sliders
        setupSliders()

        // Presets
        setupPresets()

        // Gemini AI Integration
        binding.btnGeminiAi.setOnClickListener {
            val key = GeminiAiEnhancer.getSavedApiKey(this)
            if (key.isNullOrEmpty()) {
                showApiKeyInputDialog()
            } else {
                executeGeminiEnhance()
            }
        }

        // Save Enhanced Photo
        binding.btnSave.setOnClickListener {
            currentEnhancedBitmap?.let { bmp ->
                val saved = repo.saveEnhancedBitmap(bmp, originalFile)
                Toast.makeText(this, "Saved to ${saved.name}", Toast.LENGTH_LONG).show()
                finish()
            } ?: Toast.makeText(this, "Rendering in progress...", Toast.LENGTH_SHORT).show()
        }

        binding.btnBack.setOnClickListener { finish() }
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
            applyPreset(shadows = 20f, highlights = 15f, clarity = 15f, temp = 0f, sharpness = 25f)
        }
        binding.presetHdr.setOnClickListener {
            applyPreset(shadows = 55f, highlights = 45f, clarity = 40f, temp = 5f, sharpness = 45f)
        }
        binding.presetVivid.setOnClickListener {
            applyPreset(shadows = 35f, highlights = 25f, clarity = 35f, temp = 10f, sharpness = 50f)
        }
        binding.presetLowLight.setOnClickListener {
            applyPreset(shadows = 50f, highlights = 30f, clarity = 20f, temp = -5f, sharpness = 20f)
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
            delay(120) // Smooth debouncing
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
                Toast.makeText(this@PhotoReviewActivity, "Processing error: ${t.message}", Toast.LENGTH_SHORT).show()
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
            .setMessage("Get a free key from Google AI Studio. Your key will be saved securely on your device.")
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
        binding.processingIndicator.visibility = View.VISIBLE
        Toast.makeText(this, "Analyzing scene with Gemini AI...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            try {
                val aiParams = GeminiAiEnhancer.analyzeAndOptimize(this@PhotoReviewActivity, base)
                tuningParams.exposure = aiParams.exposure
                tuningParams.shadows = aiParams.shadows
                tuningParams.highlights = aiParams.highlights
                tuningParams.contrast = aiParams.contrast
                tuningParams.clarity = aiParams.clarity
                tuningParams.saturation = aiParams.saturation
                tuningParams.temperature = aiParams.temperature
                tuningParams.sharpness = aiParams.sharpness

                // Update UI sliders to mirror AI parameters
                binding.sbShadows.progress = aiParams.shadows.toInt()
                binding.sbHighlights.progress = aiParams.highlights.toInt()
                binding.sbClarity.progress = aiParams.clarity.toInt()
                binding.sbWarmth.progress = (aiParams.temperature + 50).toInt()
                binding.sbSharpness.progress = aiParams.sharpness.toInt()

                triggerRender()
                Toast.makeText(this@PhotoReviewActivity, "✨ Gemini AI Optimization Applied!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@PhotoReviewActivity, e.message ?: "AI error", Toast.LENGTH_LONG).show()
            } finally {
                binding.processingIndicator.visibility = View.GONE
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