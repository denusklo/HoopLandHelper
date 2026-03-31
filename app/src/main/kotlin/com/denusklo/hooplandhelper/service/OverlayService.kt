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
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.denusklo.hooplandhelper.R
import com.denusklo.hooplandhelper.core.AdbRelayClient
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

    private enum class CalStep { PLACE_POINTS, PREVIEW, SHOOT_BUTTON, DONE }

    private lateinit var windowManager: WindowManager
    private lateinit var shotManager: ShotManager
    private lateinit var screenCapture: ScreenCaptureService

    // AUTO button overlay
    private var overlayView: View? = null
    private var btnAuto: TextView? = null
    private var btnToggleRect: ImageView? = null
    private var initialX = 0; private var initialY = 0
    private var initialTouchX = 0f; private var initialTouchY = 0f
    private var isDragging = false

    // Bar rectangle overlay
    private var barRectView: View? = null
    private var barRectVisible: Boolean = false

    // Calibration state
    private var calView: View? = null
    private var calStep = CalStep.PLACE_POINTS
    private val calPoints = mutableListOf<Pair<Int, Int>>()
    private var barLeft = 0; private var barTop = 0
    private var barRight = 0; private var barBottom = 0

    // Calibration view refs (set in enterCalibration, cleared in exitCalibration)
    private var calTvInstruction: TextView? = null
    private var calTvStep: TextView? = null
    private var calTvCoords: TextView? = null
    private var calBtnUndo: Button? = null
    private var calBtnConfirm: Button? = null
    private var calBtnRedo: Button? = null
    private var calRectPreview: View? = null
    private var calRectBorder: View? = null
    private var calMarkers: List<TextView> = emptyList()

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

        val rootChecker = RootChecker()
        val isRooted = rootChecker.isRooted()
        val relayLatency = if (isRooted) 100L else 250L  // ms: fixed sendevent latency (instrumentation baseline)
        Log.d(TAG, "Root check: isRooted=$isRooted, latency=${relayLatency}ms")

        // ADB relay for non-root touch injection (requires: adb reverse tcp:9999 tcp:9999 + relay.py on host)
        val relay = AdbRelayClient()

        // Display info for sendevent coordinate mapping
        val realMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        val display = (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay
        display.getRealMetrics(realMetrics)
        val nW = minOf(realMetrics.widthPixels, realMetrics.heightPixels)
        val nH = maxOf(realMetrics.widthPixels, realMetrics.heightPixels)

        val injector = TouchInjector(
            rootChecker = rootChecker,
            serviceProvider = { HoopAccessibilityService.instance },
            adbRelay = relay,
            naturalWidth = nW,
            naturalHeight = nH,
            rotationProvider = {
                @Suppress("DEPRECATION")
                (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
            }
        )
        shotManager = ShotManager(
            touchInjector = injector,
            calibration = calibration,
            frameProvider = { screenCapture.acquireBarFrame() },
            initialLatencyMs = relayLatency,
            debugDir = getExternalFilesDir(null)?.absolutePath
        )
        Log.d(TAG, "ShotManager initialized, calibrated=${calibration.isCalibrated()}")
    }

    // --- AUTO BUTTON OVERLAY ---------------------------------------------------

    private fun showAutoButton() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_button, null)
        btnAuto = overlayView?.findViewById(R.id.btn_auto)
        btnToggleRect = overlayView?.findViewById(R.id.btn_toggle_rect)

        // Load rectangle visibility preference
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        barRectVisible = prefs.getBoolean("bar_rect_visible", true)

        // Update toggle button appearance
        updateToggleRectButton()

        overlayView?.findViewById<ImageView>(R.id.btn_settings)?.setOnClickListener {
            Log.d(TAG, "Settings gear tapped — entering calibration overlay mode")
            enterCalibration()
        }

        btnToggleRect?.setOnClickListener {
            toggleBarRectVisibility()
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

        // Show bar rectangle guide after a short delay (if enabled)
        overlayView?.postDelayed({ showBarRectOverlay() }, 500)
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
            Log.d(TAG, "SHOT_COMPLETE: success=$success")
            setButtonColor(if (success) Color.GREEN else Color.RED)
            overlayView?.postDelayed({ setButtonColor(Color.GRAY) }, 500)
        }
    }

    private fun setButtonColor(color: Int) {
        btnAuto?.background?.mutate()?.setTint(color)
    }

    private fun removeAutoButton() {
        removeBarRectOverlay()
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
        btnAuto = null
        btnToggleRect = null
    }

    // --- BAR RECTANGLE OVERLAY -------------------------------------------------

    private fun showBarRectOverlay() {
        // Don't show if toggled off
        if (!barRectVisible) {
            Log.d(TAG, "Bar rect overlay disabled, skipping")
            return
        }

        removeBarRectOverlay()
        val prefs = getSharedPreferences("calibration", Context.MODE_PRIVATE)
        val region = CalibrationRepository(prefs).loadBarRegion() ?: return

        // Capture one frame and verify bar region has content
        val frame = screenCapture.acquireBarFrame()
        if (frame == null) {
            Log.d(TAG, "Bar rect: no frame available, skipping overlay")
            return
        }
        val (w, h, getPixel) = Triple(frame.width, frame.height, frame.getPixel)
        var totalBrightness = 0
        var samples = 0
        for (y in 0 until h step (h / 3).coerceAtLeast(1)) {
            for (x in 0 until w step (w / 10).coerceAtLeast(1)) {
                val p = getPixel(x, y)
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                totalBrightness += r + g + b
                samples++
            }
        }
        val avgBrightness = if (samples > 0) totalBrightness / samples else 0
        Log.d(TAG, "Bar rect fit check: avg brightness=$avgBrightness from $samples samples (threshold=30)")
        if (avgBrightness < 30) {
            Log.d(TAG, "Bar rect: region appears empty, not showing overlay")
            return
        }

        val width = region.right - region.left
        val height = region.bottom - region.top
        if (width <= 0 || height <= 0) return

        val rectView = View(this)
        rectView.setBackgroundResource(R.drawable.bar_rect_border)
        val params = WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = region.left
        params.y = region.top
        windowManager.addView(rectView, params)
        barRectView = rectView
        Log.d(TAG, "Bar rect overlay shown at (${region.left},${region.top}) ${width}x${height}")
    }

    private fun removeBarRectOverlay() {
        barRectView?.let { windowManager.removeView(it) }
        barRectView = null
    }

    private fun updateToggleRectButton() {
        // Use green tint when visible, gray when hidden
        btnToggleRect?.setColorFilter(
            if (barRectVisible) 0xFF00FF00.toInt() else 0xFFCCCCCC.toInt()
        )
    }

    private fun toggleBarRectVisibility() {
        barRectVisible = !barRectVisible
        updateToggleRectButton()

        // Save preference
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("bar_rect_visible", barRectVisible).apply()

        if (barRectVisible) {
            showBarRectOverlay()
            Log.d(TAG, "Bar rect overlay enabled")
        } else {
            removeBarRectOverlay()
            Log.d(TAG, "Bar rect overlay disabled")
        }
    }

    // --- CALIBRATION OVERLAY (4-point with drag) -------------------------------

    private fun enterCalibration() {
        removeAutoButton()
        calStep = CalStep.PLACE_POINTS
        calPoints.clear()
        barLeft = 0; barTop = 0; barRight = 0; barBottom = 0

        calView = LayoutInflater.from(this).inflate(R.layout.calibration_overlay, null)

        // Store view refs
        calTvInstruction = calView!!.findViewById(R.id.tv_cal_instruction)
        calTvStep = calView!!.findViewById(R.id.tv_cal_step)
        calTvCoords = calView!!.findViewById(R.id.tv_cal_coords)
        calBtnUndo = calView!!.findViewById(R.id.btn_cal_undo)
        calBtnConfirm = calView!!.findViewById(R.id.btn_cal_confirm)
        calBtnRedo = calView!!.findViewById(R.id.btn_cal_redo)
        calRectPreview = calView!!.findViewById(R.id.cal_rect_preview)
        calRectBorder = calView!!.findViewById(R.id.cal_rect_border)
        calMarkers = listOf(
            calView!!.findViewById(R.id.cal_marker_1),
            calView!!.findViewById(R.id.cal_marker_2),
            calView!!.findViewById(R.id.cal_marker_3),
            calView!!.findViewById(R.id.cal_marker_4)
        )

        // Setup drag on each marker
        calMarkers.forEachIndexed { index, marker -> setupMarkerDrag(marker, index) }

        calView!!.findViewById<Button>(R.id.btn_cal_cancel).setOnClickListener {
            Log.d(TAG, "Calibration cancelled")
            exitCalibration()
        }

        calView!!.findViewById<Button>(R.id.btn_cal_reset).setOnClickListener {
            CalibrationRepository(getSharedPreferences("calibration", Context.MODE_PRIVATE)).clearAll()
            resetCalibrationState()
            Log.d(TAG, "Calibration reset")
        }

        calBtnUndo!!.setOnClickListener {
            if (calPoints.isNotEmpty() && calStep == CalStep.PLACE_POINTS) {
                val removed = calPoints.removeAt(calPoints.size - 1)
                calMarkers[calPoints.size].visibility = View.GONE
                Log.d(TAG, "Undo point ${calPoints.size + 1} at (${removed.first}, ${removed.second})")
                updateCalUI()
            }
        }

        calBtnConfirm!!.setOnClickListener {
            if (calStep == CalStep.PREVIEW) {
                Log.d(TAG, "Calibration confirmed — region=($barLeft,$barTop,$barRight,$barBottom)")
                calStep = CalStep.SHOOT_BUTTON
                calRectPreview?.visibility = View.GONE
                calRectBorder?.visibility = View.GONE
                calBtnConfirm?.visibility = View.GONE
                calBtnRedo?.visibility = View.GONE
                updateCalUI()
            }
        }

        calBtnRedo!!.setOnClickListener {
            if (calStep == CalStep.PREVIEW) {
                Log.d(TAG, "Calibration redo — clearing points")
                resetCalibrationState()
            }
        }

        val calRoot = calView!!.findViewById<View>(R.id.calibration_root)
        calRoot.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val x = event.rawX.toInt()
                val y = event.rawY.toInt()
                calTvCoords?.text = "x=$x y=$y"
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
        updateCalUI()
        Log.d(TAG, "Calibration overlay shown — place 4 points on the shooting bar")
    }

    private fun setupMarkerDrag(marker: TextView, index: Int) {
        var startRawX = 0f
        var startRawY = 0f
        var startMarkerX = 0f
        var startMarkerY = 0f
        var dragged = false

        marker.setOnTouchListener { _, event ->
            // Only allow drag in PLACE_POINTS or PREVIEW steps
            if (calStep != CalStep.PLACE_POINTS && calStep != CalStep.PREVIEW) return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startMarkerX = marker.x
                    startMarkerY = marker.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    if (Math.abs(dx) > 3 || Math.abs(dy) > 3) dragged = true
                    if (dragged) {
                        marker.x = startMarkerX + dx
                        marker.y = startMarkerY + dy
                        val newX = (marker.x + 22).toInt()
                        val newY = (marker.y + 22).toInt()
                        if (index < calPoints.size) {
                            calPoints[index] = Pair(newX, newY)
                        }
                        // Recalculate and show preview if 4 points placed
                        if (calPoints.size == 4) {
                            recalcBBox()
                            showRectPreview()
                            if (calStep == CalStep.PLACE_POINTS) {
                                calStep = CalStep.PREVIEW
                                calBtnConfirm?.visibility = View.VISIBLE
                                calBtnRedo?.visibility = View.VISIBLE
                            }
                            updateCalUI()
                        }
                        calTvCoords?.text = "Point ${index + 1}: x=$newX y=$newY"
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragged) {
                        val newX = (marker.x + 22).toInt()
                        val newY = (marker.y + 22).toInt()
                        Log.d(TAG, "Marker ${index + 1} dragged to ($newX, $newY)")
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun handleCalTap(x: Int, y: Int) {
        when (calStep) {
            CalStep.PLACE_POINTS -> {
                if (calPoints.size < 4) {
                    calPoints.add(Pair(x, y))
                    val idx = calPoints.size - 1
                    calMarkers[idx].visibility = View.VISIBLE
                    calMarkers[idx].x = x.toFloat() - 22
                    calMarkers[idx].y = y.toFloat() - 22
                    Log.d(TAG, "Point ${calPoints.size} placed at ($x, $y)")

                    if (calPoints.size == 4) {
                        recalcBBox()
                        showRectPreview()
                        calStep = CalStep.PREVIEW
                        calBtnConfirm?.visibility = View.VISIBLE
                        calBtnRedo?.visibility = View.VISIBLE
                    }
                }
                updateCalUI()
            }
            CalStep.SHOOT_BUTTON -> {
                val prefs = getSharedPreferences("calibration", Context.MODE_PRIVATE)
                val repo = CalibrationRepository(prefs)
                val region = BarRegion(barLeft, barTop, barRight, barBottom)
                repo.saveBarRegion(region)
                repo.saveGreenHsv(HsvRange(hue = 120f, saturation = 0.7f, value = 0.7f))
                repo.saveShootPosition(ShootPosition(x, y))
                Log.d(TAG, "SHOOT_BUTTON tapped at ($x, $y) — all calibration saved, region=(${region.left},${region.top},${region.right},${region.bottom})")
                calStep = CalStep.DONE
                updateCalUI()
            }
            CalStep.PREVIEW, CalStep.DONE -> { /* no tap action */ }
        }

        if (calStep == CalStep.DONE) {
            Log.d(TAG, "Calibration complete! All data saved. Restarting screen capture with new calibration.")
            exitCalibration()
        }
    }

    private fun recalcBBox() {
        barLeft = calPoints.minOf { it.first }
        barTop = calPoints.minOf { it.second }
        barRight = calPoints.maxOf { it.first }
        barBottom = calPoints.maxOf { it.second }
    }

    private fun showRectPreview() {
        val preview = calRectPreview ?: return
        val border = calRectBorder ?: return
        val width = barRight - barLeft
        val height = barBottom - barTop
        if (width > 0 && height > 0) {
            preview.x = barLeft.toFloat()
            preview.y = barTop.toFloat()
            val lp = preview.layoutParams
            lp.width = width
            lp.height = height
            preview.layoutParams = lp
            preview.visibility = View.VISIBLE

            border.x = barLeft.toFloat()
            border.y = barTop.toFloat()
            val lp2 = border.layoutParams
            lp2.width = width
            lp2.height = height
            border.layoutParams = lp2
            border.visibility = View.VISIBLE
        }
    }

    private fun resetCalibrationState() {
        calStep = CalStep.PLACE_POINTS
        calPoints.clear()
        barLeft = 0; barTop = 0; barRight = 0; barBottom = 0
        calMarkers.forEach { it.visibility = View.GONE }
        calRectPreview?.visibility = View.GONE
        calRectBorder?.visibility = View.GONE
        calBtnUndo?.visibility = View.GONE
        calBtnConfirm?.visibility = View.GONE
        calBtnRedo?.visibility = View.GONE
        calTvCoords?.text = ""
        updateCalUI()
    }

    private fun updateCalUI() {
        val tvInstruction = calTvInstruction ?: return
        val tvStep = calTvStep ?: return
        val btnUndo = calBtnUndo ?: return

        when (calStep) {
            CalStep.PLACE_POINTS -> {
                tvInstruction.text = "Tap point ${calPoints.size + 1} of 4 on the shooting bar"
                tvStep.text = "${calPoints.size}/4"
                btnUndo.visibility = if (calPoints.isNotEmpty()) View.VISIBLE else View.GONE
            }
            CalStep.PREVIEW -> {
                tvInstruction.text = "Drag markers to adjust. Confirm or Redo"
                tvStep.text = "4/4"
            }
            CalStep.SHOOT_BUTTON -> {
                tvInstruction.text = "Tap the SHOOT button in Hoop Land"
                tvStep.text = "Shoot"
                btnUndo.visibility = View.GONE
            }
            CalStep.DONE -> {
                tvInstruction.text = "Done! Calibrating..."
                tvStep.text = "Done"
            }
        }
    }

    private fun exitCalibration() {
        calView?.let { windowManager.removeView(it) }
        calView = null
        calTvInstruction = null; calTvStep = null; calTvCoords = null
        calBtnUndo = null; calBtnConfirm = null; calBtnRedo = null
        calRectPreview = null; calRectBorder = null; calMarkers = emptyList()
        showAutoButton()
    }

    // --- LIFECYCLE --------------------------------------------------------------

    override fun onDestroy() {
        screenCapture.stop()
        removeBarRectOverlay()
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
