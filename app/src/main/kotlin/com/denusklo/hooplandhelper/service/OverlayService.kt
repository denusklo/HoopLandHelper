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
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.denusklo.hooplandhelper.R
import com.denusklo.hooplandhelper.core.ShotManager
import com.denusklo.hooplandhelper.core.TouchInjector
import com.denusklo.hooplandhelper.data.*
import com.denusklo.hooplandhelper.utils.RootChecker

class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "hoopland_overlay"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val TAG = "HoopLandHelper"
    }

    private enum class CalStep { BAR_LEFT, BAR_RIGHT, SHOOT_BUTTON, DONE }

    private lateinit var windowManager: WindowManager
    private lateinit var shotManager: ShotManager
    private lateinit var screenCapture: ScreenCaptureService

    // AUTO button overlay
    private var overlayView: View? = null
    private var btnAuto: TextView? = null
    private var initialX = 0; private var initialY = 0
    private var initialTouchX = 0f; private var initialTouchY = 0f
    private var isDragging = false

    // Calibration overlay
    private var calView: View? = null
    private var calStep = CalStep.BAR_LEFT
    private var barLeft = 0; private var barTop = 0
    private var barRight = 0; private var barBottom = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, buildNotification())
        setupShotManager()
        showAutoButton()
        Log.d(TAG, "OverlayService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode != 0 && data != null) {
            val prefs = getSharedPreferences("calibration", Context.MODE_PRIVATE)
            val region = CalibrationRepository(prefs).loadBarRegion()
            if (region != null) {
                screenCapture.start(resultCode, data, region)
                Log.d(TAG, "Screen capture started, bar region: left=${region.left} top=${region.top} right=${region.right} bottom=${region.bottom}")
            } else {
                Log.w(TAG, "Screen capture NOT started — no bar region calibrated yet")
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
        Log.d(TAG, "ShotManager initialized, calibrated=${calibration.isCalibrated()}")
    }

    // --- AUTO BUTTON OVERLAY ---------------------------------------------------

    private fun showAutoButton() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_button, null)
        btnAuto = overlayView?.findViewById(R.id.btn_auto)

        overlayView?.findViewById<ImageView>(R.id.btn_settings)?.setOnClickListener {
            Log.d(TAG, "Settings gear tapped — entering calibration overlay mode")
            enterCalibration()
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 100 }

        overlayView?.setOnTouchListener { _, event -> handleAutoTouch(event, params) }
        windowManager.addView(overlayView, params)
    }

    private fun handleAutoTouch(event: MotionEvent, params: WindowManager.LayoutParams): Boolean {
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
        Log.d(TAG, "AUTO tapped — shooting...")
        shotManager.shoot { success ->
            Log.d(TAG, "Shot result: ${if (success) "PERFECT" else "MISSED/TIMEOUT"}")
            setButtonColor(if (success) Color.GREEN else Color.RED)
            overlayView?.postDelayed({ setButtonColor(Color.GRAY) }, 500)
        }
    }

    private fun setButtonColor(color: Int) {
        btnAuto?.background?.mutate()?.setTint(color)
    }

    private fun removeAutoButton() {
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
        btnAuto = null
    }

    // --- CALIBRATION OVERLAY ---------------------------------------------------

    private fun enterCalibration() {
        removeAutoButton()
        calStep = CalStep.BAR_LEFT
        barLeft = 0; barTop = 0; barRight = 0; barBottom = 0

        calView = LayoutInflater.from(this).inflate(R.layout.calibration_overlay, null)
        val tvInstruction = calView!!.findViewById<TextView>(R.id.tv_cal_instruction)
        val tvStep = calView!!.findViewById<TextView>(R.id.tv_cal_step)
        val tvCoords = calView!!.findViewById<TextView>(R.id.tv_cal_coords)
        val crosshair = calView!!.findViewById<View>(R.id.cal_crosshair)

        calView!!.findViewById<Button>(R.id.btn_cal_cancel).setOnClickListener {
            Log.d(TAG, "Calibration cancelled")
            exitCalibration()
        }

        calView!!.findViewById<Button>(R.id.btn_cal_reset).setOnClickListener {
            CalibrationRepository(getSharedPreferences("calibration", Context.MODE_PRIVATE)).clearAll()
            calStep = CalStep.BAR_LEFT
            barLeft = 0; barTop = 0; barRight = 0; barBottom = 0
            tvInstruction.text = "Tap the LEFT edge of the shooting meter bar"
            tvStep.text = "1/3"
            tvCoords.text = ""
            crosshair.visibility = View.GONE
            Log.d(TAG, "Calibration reset")
        }

        val calRoot = calView!!.findViewById<View>(R.id.calibration_root)
        calRoot.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val x = event.rawX.toInt()
                val y = event.rawY.toInt()
                tvCoords.text = "x=$x y=$y"
                crosshair.visibility = View.VISIBLE
                crosshair.x = x.toFloat() - 10
                crosshair.y = y.toFloat() - 10
                handleCalTap(x, y)
            }
            true
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(calView, params)
        updateCalInstruction(tvInstruction, tvStep)
        Log.d(TAG, "Calibration overlay shown — step 1/3: tap bar left edge")
    }

    private fun handleCalTap(x: Int, y: Int) {
        val prefs = getSharedPreferences("calibration", Context.MODE_PRIVATE)
        val repo = CalibrationRepository(prefs)
        val tvInstruction = calView?.findViewById<TextView>(R.id.tv_cal_instruction) ?: return
        val tvStep = calView?.findViewById<TextView>(R.id.tv_cal_step) ?: return

        when (calStep) {
            CalStep.BAR_LEFT -> {
                barLeft = x; barTop = y
                Log.d(TAG, "Cal step 1: BAR_LEFT tapped at ($x, $y)")
                calStep = CalStep.BAR_RIGHT
            }
            CalStep.BAR_RIGHT -> {
                barRight = x; barBottom = y + 20
                val region = BarRegion(barLeft, barTop, barRight, barBottom)
                repo.saveBarRegion(region)
                // Save default green HSV range (hue 80-160, saturation > 0.3, value > 0.3)
                repo.saveGreenHsv(HsvRange(hue = 120f, saturation = 0.7f, value = 0.7f))
                Log.d(TAG, "Cal step 2: BAR_RIGHT tapped at ($x, $y) -> region=(${region.left},${region.top},${region.right},${region.bottom}), green HSV auto-set")
                calStep = CalStep.SHOOT_BUTTON
            }
            CalStep.SHOOT_BUTTON -> {
                val pos = ShootPosition(x, y)
                repo.saveShootPosition(pos)
                Log.d(TAG, "Cal step 3: SHOOT_BUTTON tapped at ($x, $y)")
                calStep = CalStep.DONE
            }
            CalStep.DONE -> {}
        }

        updateCalInstruction(tvInstruction, tvStep)

        if (calStep == CalStep.DONE) {
            Log.d(TAG, "Calibration complete! All data saved. Restarting screen capture with new calibration.")
            exitCalibration()
        }
    }

    private fun updateCalInstruction(tvInstruction: TextView, tvStep: TextView) {
        tvInstruction.text = when (calStep) {
            CalStep.BAR_LEFT    -> "Tap the LEFT edge of the shooting meter bar"
            CalStep.BAR_RIGHT   -> "Tap the RIGHT edge of the shooting meter bar"
            CalStep.SHOOT_BUTTON-> "Tap the SHOOT button in Hoop Land"
            CalStep.DONE        -> "Done! Calibrating..."
        }
        tvStep.text = "${calStep.ordinal + 1}/3"
    }

    private fun exitCalibration() {
        calView?.let { windowManager.removeView(it) }
        calView = null
        showAutoButton()
    }

    // --- LIFECYCLE --------------------------------------------------------------

    override fun onDestroy() {
        screenCapture.stop()
        overlayView?.let { windowManager.removeView(it) }
        calView?.let { windowManager.removeView(it) }
        super.onDestroy()
        Log.d(TAG, "OverlayService destroyed")
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
