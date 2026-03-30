package com.denusklo.hooplandhelper.core

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
    private var isRunning = false

    fun shoot(onResult: (Boolean) -> Unit) {
        if (!calibration.isCalibrated()) { onResult(false); return }
        if (isRunning) return
        isRunning = true

        val shootPos = calibration.loadShootPosition()!!
        val greenHsv = calibration.loadGreenHsv()!!
        val detector = if (isGreenPixelOverride != null) {
            GreenZoneDetector(greenHsv, isGreenPixelOverride)
        } else {
            GreenZoneDetector(greenHsv)
        }

        scope.launch {
            touchInjector.hold(shootPos.x, shootPos.y, timeoutMs)
            val deadline = System.currentTimeMillis() + timeoutMs
            var detected = false

            while (System.currentTimeMillis() < deadline) {
                val frame = frameProvider()
                if (frame != null) {
                    val (w, h, getPixel) = frame
                    if (detector.isGreenZoneAtCursor(w, h, getPixel)) {
                        detected = true
                        break
                    }
                }
                delay(16L) // ~60 fps
            }

            touchInjector.release()
            isRunning = false
            onResult(detected)
        }
    }

    fun cancel() {
        if (isRunning) {
            touchInjector.release()
            isRunning = false
        }
    }
}
