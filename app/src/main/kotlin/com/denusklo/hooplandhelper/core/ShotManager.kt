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
    private val relayLatencyMs: Long = 120L,
    private val holdSetupLatencyMs: Long = 0L,
    private val isGreenPixelOverride: ((Int) -> Boolean)? = null,
    private val debugDir: String? = null,
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
            GreenZoneDetector(greenHsv, isGreenPixelOverride, debugDir)
        } else {
            GreenZoneDetector(greenHsv, debugDir = debugDir)
        }

        Log.d(TAG, "Starting shot — position=(${shootPos.x},${shootPos.y}), relayLatency=${relayLatencyMs}ms")
        detector.resetDebug()

        val shotStartTime = System.currentTimeMillis()

        currentJob = scope.launch {
            // STEP 1: HOLD IMMEDIATELY to trigger the shooting bar animation
            touchInjector.hold(shootPos.x, shootPos.y, timeoutMs)
            Log.d(TAG, "HOLD started (waiting for shooting bar to appear)")

            val deadline = shotStartTime + timeoutMs

            // Give the bar animation time to start (skip first few frames)
            delay(200)

            // STEP 2: Wait for cursor + green zone to appear
            var firstAnalysis: GreenZoneDetector.BarAnalysis? = null
            var firstTime = 0L
            var frameCount = 0

            while (isActive && System.currentTimeMillis() < deadline) {
                val frame = frameProvider()
                if (frame != null) {
                    val (w, h, getPixel) = frame
                    frameCount++
                    val analysis = detector.analyzeBar(w, h, getPixel)

                    if (analysis.hasCursor && analysis.hasGreenZone) {
                        firstAnalysis = analysis
                        firstTime = System.currentTimeMillis()
                        val timeFromHoldStart = firstTime - shotStartTime
                        Log.d(TAG, "Cursor+green detected: cursor at x=${analysis.cursorX}, green ${analysis.greenLeft}..${analysis.greenRight}, timeFromHoldStart=${timeFromHoldStart}ms, frame #$frameCount")
                        break
                    } else if (analysis.hasCursor) {
                        Log.d(TAG, "Cursor at x=${analysis.cursorX} but no green zone yet (frame #$frameCount)")
                    } else if (frameCount % 10 == 0) {
                        Log.d(TAG, "Waiting for cursor (frame #$frameCount)...")
                    }
                }
                delay(16L)
            }

            if (firstAnalysis == null) {
                Log.d(TAG, "No cursor+green found within timeout — releasing")
                touchInjector.release()
                isRunning = false
                if (isActive) onResult(false)
                return@launch
            }

            // STEP 3: Get second cursor position to measure speed
            var secondAnalysis: GreenZoneDetector.BarAnalysis? = null
            var secondTime = 0L

            while (isActive && System.currentTimeMillis() < deadline) {
                val frame = frameProvider()
                if (frame != null) {
                    val (w, h, getPixel) = frame
                    val analysis = detector.analyzeBar(w, h, getPixel)
                    if (analysis.hasCursor) {
                        secondAnalysis = analysis
                        secondTime = System.currentTimeMillis()
                        Log.d(TAG, "Second cursor: x=${analysis.cursorX} (moved ${analysis.cursorX - firstAnalysis.cursorX} px in ${secondTime - firstTime}ms)")
                        break
                    }
                }
                delay(16L)
            }

            if (secondAnalysis == null) {
                Log.d(TAG, "Could not get second cursor frame — releasing immediately")
                touchInjector.release()
                isRunning = false
                if (isActive) onResult(false)
                return@launch
            }

            // STEP 4: Calculate time until green zone center
            val interval = secondTime - firstTime
            val timeUntilGreen = detector.calculateTimeToGreenMs(
                firstAnalysis, secondAnalysis, interval
            )

            if (timeUntilGreen < 0) {
                Log.d(TAG, "Already past green zone — releasing immediately")
                touchInjector.release()
                isRunning = false
                if (isActive) onResult(false)
                return@launch
            }

            // STEP 5: Wait the calculated time, then release
            val releaseDelay = (timeUntilGreen - relayLatencyMs - holdSetupLatencyMs).coerceAtLeast(0)

            Log.d(TAG, "Calculated: timeUntilGreen=${timeUntilGreen}ms, waiting ${releaseDelay}ms before release (relayLatency=${relayLatencyMs}ms, holdSetupLatency=${holdSetupLatencyMs}ms)")

            if (releaseDelay > 0) {
                delay(releaseDelay)
            }

            Log.d(TAG, "RELEASE NOW (canceling hold)")
            touchInjector.release()
            isRunning = false
            if (isActive) onResult(true)
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
