package com.example.humanoiddetector

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val service = Intent(this, CaptureService::class.java).apply {
                    putExtra(CaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(CaptureService.EXTRA_RESULT_DATA, result.data)
                }
                startForegroundService(service)
                statusText.text = getString(R.string.status_running)
            } else {
                Toast.makeText(this, R.string.projection_denied, Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        val startButton: Button = findViewById(R.id.startButton)
        val stopButton: Button = findViewById(R.id.stopButton)
        val overlayButton: Button = findViewById(R.id.overlayButton)

        startButton.setOnClickListener { requestPermissionsAndStart() }

        stopButton.setOnClickListener {
            stopService(Intent(this, CaptureService::class.java))
            statusText.text = getString(R.string.status_stopped)
        }

        overlayButton.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                openOverlaySettings()
            } else {
                Toast.makeText(this, R.string.overlay_granted, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        statusText.text = if (CaptureService.isRunning) {
            getString(R.string.status_running)
        } else {
            getString(R.string.status_stopped)
        }
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun requestPermissionsAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.need_overlay, Toast.LENGTH_LONG).show()
            openOverlaySettings()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionsAndStart()
        }
    }
}
