package com.astracam.dualdevice.gallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.astracam.dualdevice.databinding.ActivityPhotoReviewBinding
import com.astracam.dualdevice.device.DeviceProfileManager
import com.astracam.dualdevice.processing.ImageProcessingPipeline
import com.astracam.dualdevice.storage.ExifMetadataHandler
import com.astracam.dualdevice.storage.PhotoRepository
import kotlinx.coroutines.launch
import java.io.File

class PhotoReviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhotoReviewBinding
    private var originalFile: File? = null
    private var originalBitmap: Bitmap? = null
    private var enhancedBitmap: Bitmap? = null

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
        val orientation = ExifMetadataHandler.getExifOrientationDegrees(originalFile!!)
        val profile = DeviceProfileManager.getProfile(this)
        val pipeline = ImageProcessingPipeline(profile)
        val repo = PhotoRepository(this)

        // Display original initially
        originalBitmap = BitmapFactory.decodeFile(path)
        binding.imagePreview.setImageBitmap(originalBitmap)

        // Run computational photography enhancement asynchronously
        binding.processingIndicator.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val enhanced = pipeline.processSinglePhoto(originalFile!!, orientation)
                enhancedBitmap = enhanced
                binding.imagePreview.setImageBitmap(enhanced)
            } catch (t: Throwable) {
                Toast.makeText(this@PhotoReviewActivity, "Processing error: ${t.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.processingIndicator.visibility = View.GONE
            }
        }

        binding.toggleComparison.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == binding.rbOriginal.id) {
                binding.imagePreview.setImageBitmap(originalBitmap)
            } else {
                enhancedBitmap?.let { binding.imagePreview.setImageBitmap(it) }
            }
        }

        binding.btnSave.setOnClickListener {
            enhancedBitmap?.let { bmp ->
                repo.saveEnhancedBitmap(bmp, originalFile)
                Toast.makeText(this, "Saved enhanced photo to Pictures/AstraCam/Enhanced", Toast.LENGTH_LONG).show()
                finish()
            } ?: Toast.makeText(this, "Image enhancement in progress...", Toast.LENGTH_SHORT).show()
        }

        binding.btnBack.setOnClickListener { finish() }
    }

    companion object {
        const val EXTRA_PHOTO_PATH = "extra_photo_path"
    }
}