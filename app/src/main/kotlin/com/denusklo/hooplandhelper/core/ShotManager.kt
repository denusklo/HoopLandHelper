package com.denusklo.hooplandhelper.core

import android.util.Log
import com.denusklo.hooplandhelper.data.CalibrationRepository
import kotlinx.coroutines.*

typealias FrameProvider = () -> Triple<Int, Int, (Int, Int) -> Int>?

class ShotManager(
    private val touchInjector: TouchInjector,
    private val calibration: CalibrationRepository,
    private val frameProvider: FrameProvider,
    private val timeoutMs: Long = 3000L,
    private val isGreenPixelOverride: ((Int) -> Boolean)? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private var currentJob: Job? = null
    private var isRunning = false

    fun shoot(onResult: (Boolean) -> Unit) {
        if (!calibration.isCalibrated()) {
            Log.d(TAG, "shoot() called but not calibrated")
            onResult(false); return
        }
        if (isRunning) {
            Log.d(TAG, "shoot() called but already running, ignoring")
            return
        }
        isRunning = true

        val shootPos = calibration.loadShootPosition()!!
        val greenHsv = calibration.loadGreenHsv()!!
        val detector = if (isGreenPixelOverride != null) {
            GreenZoneDetector(greenHsv, isGreenPixelOverride)
        } else {
            GreenZoneDetector(greenHsv)
        }

        Log.d(TAG, "Starting shot — position=(${shootPos.x},${shootPos.y}), timeout=${timeoutMs}ms")

        currentJob = scope.launch {
            touchInjector.hold(shootPos.x, shootPos.y, timeoutMs)
            val deadline = System.currentTimeMillis() + timeoutMs
            var detected = false
            var frameCount = 0

            while (isActive && System.currentTimeMillis() < deadline) {
                val frame = frameProvider()
                if (frame != null) {
                    val (w, h, getPixel) = frame
                    frameCount++
                    val result = detector.isGreenZoneAtCursor(w, h, getPixel)
                    if (frameCount % 10 == 0) {
                        Log.d(TAG, "Frame check #$frameCount: cursor detection result=$result")
                    }
                    if (result) {
                        val elapsed = timeoutMs - (deadline - System.currentTimeMillis())
                        Log.d(TAG, "GREEN DETECTED after ${elapsed}ms")
                        detected = true
                        break
                    }
                }
                delay(16L) // ~60 fps
            }

            if (!detected) {
                Log.d(TAG, "Shot timed out after ${timeoutMs}ms — no green detected")
            }

            touchInjector.release()
            isRunning = false
            if (isActive) onResult(detected)
        }
    }

    fun cancel() {
        Log.d(TAG, "Shot cancelled")
        currentJob?.cancel()
        if (isRunning) {
            touchInjector.release()
            isRunning = false
        }
    }

    companion object {
        private const val TAG = "HoopLandHelper"
    }
}
