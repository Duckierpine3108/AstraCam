package com.astracam.dualdevice

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.PreviewView
import androidx.lifecycle.lifecycleScope
import com.astracam.dualdevice.camera.CameraCaptureController
import com.astracam.dualdevice.camera.CameraManagerController
import com.astracam.dualdevice.camera.CameraPermissionManager
import com.astracam.dualdevice.camera.HdrCaptureCoordinator
import com.astracam.dualdevice.device.DeviceProfile
import com.astracam.dualdevice.device.DeviceProfileManager
import com.astracam.dualdevice.diagnostics.DiagnosticsActivity
import com.astracam.dualdevice.gallery.PhotoReviewActivity
import com.astracam.dualdevice.processing.ImageProcessingPipeline
import com.astracam.dualdevice.storage.PhotoRepository
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var profile: DeviceProfile
    private lateinit var permissionManager: CameraPermissionManager
    private lateinit var cameraManager: CameraManagerController
    private lateinit var captureController: CameraCaptureController
    private lateinit var photoRepository: PhotoRepository
    private lateinit var hdrCoordinator: HdrCaptureCoordinator
    private lateinit var processingPipeline: ImageProcessingPipeline

    private var imageCapture: ImageCapture? = null
    private var isCapturing: Boolean = false
    private var isHdrEnabled: Boolean = false
    private var currentFlashMode: Int = ImageCapture.FLASH_MODE_OFF

    // View References
    private lateinit var viewFinder: PreviewView
    private lateinit var btnShutter: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var btnGallery: ImageButton
    private lateinit var btnSwitchCamera: ImageButton
    private lateinit var statusBanner: TextView
    private var btnFlash: ImageButton? = null
    private var btnHdr: ImageButton? = null
    private var exposureSeekBar: SeekBar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        profile = DeviceProfileManager.getProfile(this)

        // Select layout based on screen profile
        if (profile.isRoundScreen) {
            setContentView(R.layout.activity_main_watch)
        } else {
            setContentView(R.layout.activity_main_phone)
        }

        bindViews()

        permissionManager = CameraPermissionManager(this)
        cameraManager = CameraManagerController(this, this)
        photoRepository = PhotoRepository(this)
        captureController = CameraCaptureController(this, photoRepository)
        hdrCoordinator = HdrCaptureCoordinator(captureController)
        processingPipeline = ImageProcessingPipeline(profile)

        setupListeners()

        if (permissionManager.hasAllPermissions()) {
            startCamera()
        } else {
            permissionManager.requestPermissions()
        }
    }

    private fun bindViews() {
        viewFinder = findViewById(R.id.viewFinder)
        btnShutter = findViewById(R.id.btnShutter)
        btnSettings = findViewById(R.id.btnSettings)
        btnGallery = findViewById(R.id.btnGallery)
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera)
        statusBanner = findViewById(R.id.statusBanner)

        // Available only in phone layout
        btnFlash = findViewById(R.id.btnFlash)
        btnHdr = findViewById(R.id.btnHdr)
        exposureSeekBar = findViewById(R.id.exposureSeekBar)
    }

    private fun setupListeners() {
        btnShutter.setOnClickListener {
            if (!isCapturing) {
                takePhoto()
            }
        }

        btnSettings.setOnClickListener {
            val intent = Intent(this, DiagnosticsActivity::class.java)
            startActivity(intent)
        }

        btnGallery.setOnClickListener {
            Toast.makeText(this, "Reviewing latest captures", Toast.LENGTH_SHORT).show()
        }

        btnSwitchCamera.setOnClickListener {
            imageCapture?.let { cap ->
                val switched = cameraManager.toggleCamera(viewFinder, cap)
                if (!switched) {
                    Toast.makeText(this, "Secondary camera not available", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnFlash?.setOnClickListener {
            currentFlashMode = when (currentFlashMode) {
                ImageCapture.FLASH_MODE_OFF -> {
                    btnFlash?.setImageResource(R.drawable.ic_flash_on)
                    ImageCapture.FLASH_MODE_ON
                }
                else -> {
                    btnFlash?.setImageResource(R.drawable.ic_flash_off)
                    ImageCapture.FLASH_MODE_OFF
                }
            }
            imageCapture?.let { cameraManager.setFlashMode(it, currentFlashMode) }
        }

        btnHdr?.setOnClickListener {
            if (!profile.enableMultiFrameHdr) {
                Toast.makeText(this, "Multi-Frame HDR not supported on this profile", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            isHdrEnabled = !isHdrEnabled
            btnHdr?.setImageResource(if (isHdrEnabled) R.drawable.ic_hdr_on else R.drawable.ic_hdr_off)
            showStatus(if (isHdrEnabled) "HDR Multi-Frame Enabled" else "HDR Disabled")
        }

        exposureSeekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val evIndex = progress - 50 // -50 to +50 scaled steps
                    cameraManager.setExposureCompensation(evIndex / 10)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun startCamera() {
        cameraManager.initialize(
            onReady = {
                val rotation = windowManager.defaultDisplay.rotation
                val cap = captureController.buildImageCapture(rotation)
                imageCapture = cap
                cameraManager.bindCamera(viewFinder, cap)
            },
            onError = { t ->
                Toast.makeText(this, "Camera init error: ${t.message}", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun takePhoto() {
        val cap = imageCapture ?: return
        isCapturing = true
        showStatus(getString(R.string.status_processing))

        if (isHdrEnabled && profile.enableMultiFrameHdr) {
            // Multi-Frame HDR Exposure Bracketing (-2 EV, 0 EV, +2 EV)
            lifecycleScope.launch {
                try {
                    val bracketFiles = hdrCoordinator.captureExposureBracket(
                        cap,
                        cameraManager.getCameraControl(),
                        listOf(-2, 0, 2)
                    )
                    if (bracketFiles.isNotEmpty()) {
                        val merged = processingPipeline.processMultiFrameHdr(bracketFiles)
                        val savedFile = photoRepository.saveEnhancedBitmap(merged, bracketFiles[0])
                        openReview(savedFile)
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "HDR Capture failed: ${e.message}", Toast.LENGTH_SHORT).show()
                } finally {
                    isCapturing = false
                    hideStatus()
                }
            }
        } else {
            // Single Capture Workflow
            captureController.captureStandardPhoto(
                cap,
                onSuccess = { tempFile ->
                    isCapturing = false
                    hideStatus()
                    val savedFile = photoRepository.saveOriginalPhoto(tempFile)
                    openReview(savedFile)
                },
                onError = { exc ->
                    isCapturing = false
                    hideStatus()
                    Toast.makeText(this, "Capture failed: ${exc.message}", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    private fun openReview(file: File) {
        val intent = Intent(this, PhotoReviewActivity::class.java).apply {
            putExtra(PhotoReviewActivity.EXTRA_PHOTO_PATH, file.absolutePath)
        }
        startActivity(intent)
    }

    private fun showStatus(text: String) {
        statusBanner.text = text
        statusBanner.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        statusBanner.visibility = View.GONE
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CameraPermissionManager.REQUEST_CODE_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startCamera()
            } else {
                Toast.makeText(this, getString(R.string.permission_camera_rationale), Toast.LENGTH_LONG).show()
            }
        }
    }
}