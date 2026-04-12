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
    private val releaseLatencyMs: Long = 200L,
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

        Log.d(TAG, "Starting shot — position=(${shootPos.x},${shootPos.y}), latency=${releaseLatencyMs}ms")
        detector.resetDebug()

        val shotStartTime = System.currentTimeMillis()

        currentJob = scope.launch {
            // STEP 1: HOLD to trigger the shooting bar animation
            touchInjector.hold(shootPos.x, shootPos.y, timeoutMs)
            Log.d(TAG, "HOLD started (waiting for shooting bar to appear)")

            val deadline = shotStartTime + timeoutMs
            delay(50)

            // STEP 2: Wait for cursor + green zone to appear
            var foundGreen = false
            var targetX = 0
            var frameCount = 0

            // Speed tracking: measure only between unique cursor positions
            // to avoid duplicate frames dragging down the average speed.
            var lastUniqueX = -1
            var lastUniqueTime = 0L
            var speedSum = 0f
            var speedCount = 0
            var smoothSpeed = 0f

            while (isActive && System.currentTimeMillis() < deadline) {
                val frame = frameProvider()
                if (frame != null) {
                    val (w, h, getPixel) = frame
                    frameCount++
                    val analysis = detector.analyzeBar(w, h, getPixel)
                    val now = System.currentTimeMillis()

                    if (analysis.hasCursor && analysis.hasGreenZone) {
                        if (!foundGreen) {
                            foundGreen = true
                            lastUniqueX = analysis.cursorX
                            lastUniqueTime = now
                            targetX = analysis.greenLeft  // target left edge for max margin
                            val timeFromHoldStart = now - shotStartTime
                            Log.d(TAG, "DETECTED: cursor=${analysis.cursorX}, green=${analysis.greenLeft}..${analysis.greenRight}, target=LEFT_EDGE=$targetX, holdStart=${timeFromHoldStart}ms, frame #$frameCount")
                        }

                        val remaining = targetX - analysis.cursorX

                        // Update speed from unique cursor transitions only (skip duplicates)
                        if (analysis.cursorX != lastUniqueX && lastUniqueX >= 0) {
                            val stepDx = analysis.cursorX - lastUniqueX
                            val stepDt = now - lastUniqueTime
                            if (stepDt > 0 && stepDx > 0) {
                                val stepSpeed = stepDx.toFloat() / stepDt.toFloat()
                                speedSum += stepSpeed
                                speedCount++
                                smoothSpeed = speedSum / speedCount
                                Log.d(TAG, "SPEED: step=${stepDx}px/${stepDt}ms=${String.format("%.3f", stepSpeed)}px/ms, smooth=${String.format("%.3f", smoothSpeed)}px/ms (#$speedCount)")
                            }
                            lastUniqueX = analysis.cursorX
                            lastUniqueTime = now
                        }

                        if (remaining <= 0) {
                            // Already past target — release immediately
                            Log.d(TAG, "RELEASE NOW: cursor=${analysis.cursorX} already past target=$targetX")
                            touchInjector.release()
                            isRunning = false
                            if (isActive) onResult(true)
                            return@launch
                        }

                        // Need at least 2 unique-position speed samples for reliable prediction
                        if (speedCount >= 2) {
                            val timeToTarget = if (smoothSpeed > 0) (remaining / smoothSpeed).toLong() else Long.MAX_VALUE

                            // Log near release zone (within 60px)
                            if (remaining < 60) {
                                Log.d(TAG, "APPROACH: cursor=${analysis.cursorX}, speed=${String.format("%.3f", smoothSpeed)}px/ms, remaining=${remaining}px, timeToTarget=${timeToTarget}ms")
                            }

                            if (remaining <= smoothSpeed * releaseLatencyMs) {
                                Log.d(TAG, "RELEASE: cursor=${analysis.cursorX}, speed=${String.format("%.3f", smoothSpeed)}px/ms, remaining=${remaining}px, timeToTarget=${timeToTarget}ms <= latency=${releaseLatencyMs}ms")
                                touchInjector.release()
                                val releaseTargetX = targetX
                                // Capture release frame in background for analysis
                                scope.launch(Dispatchers.IO) {
                                    delay(200)
                                    val relFrame = frameProvider()
                                    if (relFrame != null) {
                                        val (rw, rh, rp) = relFrame
                                        val relAnalysis = detector.analyzeBar(rw, rh, rp)
                                        if (relAnalysis.hasCursor) {
                                            val inGreen = relAnalysis.hasGreenZone && relAnalysis.cursorX in relAnalysis.greenLeft..relAnalysis.greenRight
                                            val diff = relAnalysis.cursorX - releaseTargetX
                                            val verdict = when {
                                                inGreen -> "IN GREEN"
                                                diff < 0 -> "EARLY by ${-diff}px"
                                                else -> "LATE by ${diff}px"
                                            }
                                            val greenInfo = if (relAnalysis.hasGreenZone) "${relAnalysis.greenLeft}..${relAnalysis.greenRight}" else "none"
                                            Log.d(TAG, "Release analysis: cursor=${relAnalysis.cursorX}, green=$greenInfo, target=$releaseTargetX → $verdict")
                                            detector.saveReleaseFrame(rw, rh, rp, relAnalysis.cursorX, releaseTargetX)
                                        } else {
                                            Log.d(TAG, "Release frame: no cursor found (bar may have disappeared)")
                                        }
                                    }
                                }
                                isRunning = false
                                if (isActive) onResult(true)
                                return@launch
                            }
                        }

                    } else if (analysis.hasCursor && frameCount % 10 == 0) {
                        Log.d(TAG, "Cursor at ${analysis.cursorX}, no green yet (frame #$frameCount)")
                    } else if (frameCount % 20 == 0) {
                        Log.d(TAG, "Waiting for cursor (frame #$frameCount)...")
                    }
                }
                delay(8L)
            }

            Log.d(TAG, "No cursor+green found within timeout — releasing")
            touchInjector.release()
            isRunning = false
            if (isActive) onResult(false)
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
