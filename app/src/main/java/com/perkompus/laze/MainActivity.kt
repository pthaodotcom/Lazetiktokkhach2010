package com.perkompus.laze

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var btnEnableOverlay: Button
    private lateinit var btnEnableService: Button
    private lateinit var tvServiceStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnEnableOverlay = findViewById(R.id.btnEnableOverlay)
        btnEnableService = findViewById(R.id.btnEnableService)
        tvServiceStatus = findViewById(R.id.tvServiceStatus)

        btnEnableOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivity(intent)
            } else {
                // If permission granted, we can maybe toggle the overlay via the Service?
                // Or maybe this button just opens permissions.
                // Let's launch the overlay directly if service is running, otherwise tell user to start service.
                if (LazeService.isRunning) {
                    Toast.makeText(this, "Service is running. Overlay should be active.", Toast.LENGTH_SHORT).show()
                    // Alternatively, we could send a broadcast or bind to service to toggle overlay.
                    // For now, let's keep it simple.
                } else {
                    Toast.makeText(this, "Permission Granted. Please enable Accessibility Service.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnEnableService.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        if (LazeService.isRunning) {
            tvServiceStatus.text = getString(R.string.service_status_running)
            tvServiceStatus.setTextColor(getColor(R.color.teal_700))
        } else {
            tvServiceStatus.text = getString(R.string.service_status_stopped)
            tvServiceStatus.setTextColor(getColor(R.color.purple_700))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
             btnEnableOverlay.text = "Overlay Permission Granted"
             btnEnableOverlay.isEnabled = false
        } else {
             btnEnableOverlay.text = getString(R.string.enable_overlay)
             btnEnableOverlay.isEnabled = true
        }
    }
}
