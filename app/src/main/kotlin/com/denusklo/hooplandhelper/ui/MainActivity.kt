package com.denusklo.hooplandhelper.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.denusklo.hooplandhelper.R
import com.denusklo.hooplandhelper.data.CalibrationRepository
import com.denusklo.hooplandhelper.service.OverlayService

class MainActivity : AppCompatActivity() {

    private val MEDIA_PROJECTION_REQUEST = 1001
    private lateinit var tvStatus: TextView
    private lateinit var btnGrantOverlay: Button
    private lateinit var btnStart: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tv_status)
        btnGrantOverlay = findViewById(R.id.btn_grant_overlay)
        btnStart = findViewById(R.id.btn_start)

        btnGrantOverlay.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        }

        findViewById<Button>(R.id.btn_grant_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnStart.setOnClickListener { requestMediaProjection() }

        findViewById<Button>(R.id.btn_calibrate).setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        val hasOverlay = Settings.canDrawOverlays(this)
        val isCalibrated = CalibrationRepository(
            getSharedPreferences("calibration", Context.MODE_PRIVATE)
        ).isCalibrated()

        btnGrantOverlay.visibility = if (hasOverlay) View.GONE else View.VISIBLE
        btnStart.visibility = if (hasOverlay) View.VISIBLE else View.GONE
        tvStatus.text = when {
            !hasOverlay -> "Grant overlay permission first."
            !isCalibrated -> "Overlay granted. Please calibrate before starting."
            else -> "Ready. Open Hoop Land, then tap Start."
        }
    }

    private fun requestMediaProjection() {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mgr.createScreenCaptureIntent(), MEDIA_PROJECTION_REQUEST)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == MEDIA_PROJECTION_REQUEST && resultCode == Activity.RESULT_OK && data != null) {
            startForegroundService(Intent(this, OverlayService::class.java).apply {
                putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
                putExtra(OverlayService.EXTRA_RESULT_DATA, data)
            })
            finish()
        }
    }
}
