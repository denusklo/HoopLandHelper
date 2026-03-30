package com.denusklo.hooplandhelper.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.denusklo.hooplandhelper.R
import com.denusklo.hooplandhelper.core.ShotManager
import com.denusklo.hooplandhelper.core.TouchInjector
import com.denusklo.hooplandhelper.data.CalibrationRepository
import com.denusklo.hooplandhelper.ui.CalibrationActivity
import com.denusklo.hooplandhelper.utils.RootChecker

class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "hoopland_overlay"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var btnAuto: TextView
    private lateinit var shotManager: ShotManager
    private lateinit var screenCapture: ScreenCaptureService

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, buildNotification())
        setupShotManager()
        setupOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode != 0 && data != null) {
            val prefs = getSharedPreferences("calibration", Context.MODE_PRIVATE)
            val region = CalibrationRepository(prefs).loadBarRegion()
            if (region != null) {
                screenCapture.start(resultCode, data, region)
            }
        }
        return START_NOT_STICKY
    }

    private fun setupShotManager() {
        screenCapture = ScreenCaptureService(this)
        val prefs = getSharedPreferences("calibration", Context.MODE_PRIVATE)
        val calibration = CalibrationRepository(prefs)
        val injector = TouchInjector(
            rootChecker = RootChecker(),
            serviceProvider = { HoopAccessibilityService.instance }
        )
        shotManager = ShotManager(
            touchInjector = injector,
            calibration = calibration,
            frameProvider = { screenCapture.acquireBarFrame() }
        )
    }

    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_button, null)
        btnAuto = overlayView.findViewById(R.id.btn_auto)

        overlayView.findViewById<ImageView>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 100 }

        overlayView.setOnTouchListener { _, event -> handleTouch(event, params) }
        windowManager.addView(overlayView, params)
    }

    private fun handleTouch(event: MotionEvent, params: WindowManager.LayoutParams): Boolean {
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x; initialY = params.y
                initialTouchX = event.rawX; initialTouchY = event.rawY
                isDragging = false; true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - initialTouchX).toInt()
                val dy = (event.rawY - initialTouchY).toInt()
                if (Math.abs(dx) > 5 || Math.abs(dy) > 5) isDragging = true
                if (isDragging) {
                    params.x = initialX + dx; params.y = initialY + dy
                    windowManager.updateViewLayout(overlayView, params)
                }
                true
            }
            MotionEvent.ACTION_UP -> { if (!isDragging) onAutoTapped(); true }
            else -> false
        }
    }

    private fun onAutoTapped() {
        setButtonColor(Color.YELLOW)
        shotManager.shoot { success ->
            setButtonColor(if (success) Color.GREEN else Color.RED)
            overlayView.postDelayed({ setButtonColor(Color.GRAY) }, 500)
        }
    }

    private fun setButtonColor(color: Int) {
        btnAuto.background?.mutate()?.setTint(color)
    }

    override fun onDestroy() {
        screenCapture.stop()
        if (::overlayView.isInitialized) windowManager.removeView(overlayView)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "HoopLand Helper", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HoopLand Helper")
            .setContentText("Auto-shoot active")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
}
