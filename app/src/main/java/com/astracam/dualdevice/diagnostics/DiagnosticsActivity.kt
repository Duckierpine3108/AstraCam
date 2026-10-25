package com.astracam.dualdevice.diagnostics

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.astracam.dualdevice.databinding.ActivityDiagnosticsBinding
import com.astracam.dualdevice.device.DeviceDiagnostics
import com.astracam.dualdevice.device.DeviceProfileManager
import com.astracam.dualdevice.device.DeviceProfileType

class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnosticsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val profile = DeviceProfileManager.getProfile(this)
        val report = DeviceDiagnostics.generateReport(this, profile)
        binding.txtDiagnosticsInfo.text = report

        binding.btnForceToggleProfile.setOnClickListener {
            val newOverride = if (profile.type == DeviceProfileType.H18_WATCH) {
                DeviceProfileType.REDMI_NOTE_9S
            } else {
                DeviceProfileType.H18_WATCH
            }
            DeviceProfileManager.setProfileOverride(this, newOverride)
            Toast.makeText(this, "Profile set to ${newOverride.name}. Restart app to take effect.", Toast.LENGTH_LONG).show()
            finish()
        }
    }
}