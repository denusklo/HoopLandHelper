package com.denusklo.hooplandhelper.core

import android.util.Log
import com.denusklo.hooplandhelper.data.CalibrationRepository
import kotlinx.coroutines.*

typealias FrameProvider = () -> BarFrame?

private enum class PhaseAlignMode { OFF, SHADOW, LIVE }

private data class PhasePlan(
    val boundaryCaptureNs: Long,
    val boundarySystemNs: Long,
    val targetSendFlushNs: Long,
    val targetSendIntentNs: Long,
    val plannedWaitNs: Long,
    val effectiveLatencyNs: Long,
    val bridgeOffsetNs: Long,
    val bridgeJitterNs: Long,
    val policy: String
)

/**
 * Per-shot trace data for instrumentation logging.
 * Build 6: PhasePlan architecture — shadow mode first, live mode after validation.
 */
private data class ShotTrace(
    val shotId: Int,
    val holdStartNs: Long,
    val fixedLatencyMs: Long,
    var targetMode: String,
    var decisionNs: Long = 0,
    var decisionFrameSeq: Long = 0,
    var decisionFrameTsNs: Long = 0,
    var decisionCursorX: Int = 0,
    var decisionTargetX: Int = 0,
    var decisionSpeedPxMs: Float = 0f,
    // Decision-time green zone — preserved for scoring even if stop-frame green disappears
    var decisionGreenLeft: Int = 0,
    var decisionGreenRight: Int = 0,
    var lastGreenLeft: Int = 0,
    var lastGreenRight: Int = 0,
    // Phase plan fields
    var boundaryCaptureNs: Long = 0,
    var boundarySystemNs: Long = 0,
    var targetSendIntentNs: Long = 0,
    var targetSendFlushNs: Long = 0,
    var plannedWaitMs: Float = 0f,
    var effectiveLatencyMs: Float = 0f,
    var phasePolicy: String = "",
    // Sleep trace fields
    var wakeNs: Long = 0,
    var wakeErrorNs: Long = 0,
    // Send timing
    var sendIntentNs: Long = 0,
    var sendFlushDoneNs: Long = 0,
    var sendPath: String = "",
    // Post-release fields
    var firstPostFrameSeq: Long = 0,
    var firstPostFrameTsNs: Long = 0,
    var firstPostCursorX: Int = 0,
    var stopFrameSeq: Long = 0,
    var stopFrameTsNs: Long = 0,
    var stopCursorX: Int = 0,
    var result: String = ""
)

class ShotManager(
    private val touchInjector: TouchInjector,
    private val calibration: CalibrationRepository,
    private val frameProvider: FrameProvider,
    private val timeoutMs: Long = 3000L,
    initialLatencyMs: Long = 200L,
    private val isGreenPixelOverride: ((Int) -> Boolean)? = null,
    private val debugDir: String? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private var currentJob: Job? = null
    private var isRunning = false

    // Fixed release latency (ms) — kept constant (no adaptation)
    private val releaseLatencyMs = initialLatencyMs

    // Frame clock PLL — capture-domain timestamps
    private val frameClock = FrameClockEstimator()

    // Capture-to-system clock bridge
    private val clockBridge = ClockBridgeEstimator()

    // Absolute-time sleeper for phase-aligned release
    private val absoluteSleeper: AbsoluteTimeSleeper = JvmAbsoluteTimeSleeper()

    // Expected send overhead (sendevent flush ≈ 2-3ms)
    private val expectedSendOverheadNs = 3_000_000L

    // Phase offset: tune later; 0 = target flush lands right on boundary
    private val phaseOffsetNs = 0L

    // Phase alignment mode: SHADOW = log plan but send immediately, LIVE = use absoluteSleeper
    private val phaseMode = PhaseAlignMode.SHADOW

    // Monotonic shot counter for log correlation
    private var shotCounter = 0

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

        val shotId = ++shotCounter
        val shootPos = calibration.loadShootPosition()!!
        val greenHsv = calibration.loadGreenHsv()!!
        val detector = if (isGreenPixelOverride != null) {
            GreenZoneDetector(greenHsv, isGreenPixelOverride, debugDir)
        } else {
            GreenZoneDetector(greenHsv, debugDir = debugDir)
        }

        val trace = ShotTrace(
            shotId = shotId,
            holdStartNs = System.nanoTime(),
            fixedLatencyMs = releaseLatencyMs,
            targetMode = "greenLeft"
        )

        Log.d(TAG, "SHOT_START: shotId=$shotId, mode=fixed, fixedLatencyMs=${releaseLatencyMs}, phaseMode=$phaseMode, targetMode=${trace.targetMode}, holdStartNs=${trace.holdStartNs}")
        detector.resetDebug()
        frameClock.reset()
        clockBridge.reset()

        val deadlineNano = trace.holdStartNs + timeoutMs * 1_000_000L

        currentJob = scope.launch {
            // STEP 1: HOLD to trigger the shooting bar animation
            touchInjector.hold(shootPos.x, shootPos.y, timeoutMs)
            Log.d(TAG, "HOLD started (waiting for shooting bar to appear)")

            delay(50)

            // STEP 2: Wait for cursor + green zone to appear
            var armed = false  // false-start guard: require stable green zone
            var targetX = 0
            var frameCount = 0
            var dupCount = 0

            // False-start guard: green zone must be stable for 2 unique frames
            var greenStableCount = 0
            var lastStableGreenLeft = -1
            var lastStableGreenRight = -1

            // Speed tracking: sliding window of recent cursor transitions
            // Uses CAPTURE-domain timestamps (frame.timestampNs) for cadence
            var lastUniqueX = -1
            var lastUniqueCaptureNs = 0L
            var lastObserveNs = 0L
            var speedCount = 0
            var smoothSpeed = 0f
            val recentSteps = mutableListOf<Pair<Int, Long>>() // (dx, capture_dt_ns)
            val SPEED_WINDOW = 8

            while (isActive && System.nanoTime() < deadlineNano) {
                val frame = frameProvider()
                if (frame != null) {
                    // Frame deduplication: skip if same timestamp (display 60Hz, game 30fps)
                    if (frame.isDuplicate) {
                        dupCount++
                        delay(2L)
                        continue
                    }

                    // Feed clock bridge with every unique frame
                    clockBridge.record(frame.timestampNs, frame.frameObservedNs)

                    frameCount++
                    val analysis = detector.analyzeBar(frame.width, frame.height, frame.getPixel)
                    val nowNano = System.nanoTime()

                    if (analysis.hasCursor && analysis.hasGreenZone) {
                        // False-start guard: first cursor must be in left quarter of bar
                        if (!armed) {
                            val leftQuarter = frame.width / 4
                            if (analysis.cursorX > leftQuarter) {
                                if (frameCount % 10 == 0) {
                                    Log.d(TAG, "FALSE_START_GUARD: shotId=$shotId, cursorX=${analysis.cursorX} > leftQuarter=$leftQuarter, ignoring")
                                }
                                delay(8L)
                                continue
                            }

                            // Green zone stability: must be consistent for 2 unique frames
                            if (analysis.greenLeft == lastStableGreenLeft && analysis.greenRight == lastStableGreenRight) {
                                greenStableCount++
                            } else {
                                greenStableCount = 1
                                lastStableGreenLeft = analysis.greenLeft
                                lastStableGreenRight = analysis.greenRight
                            }

                            if (greenStableCount < 2) {
                                lastUniqueX = analysis.cursorX
                                lastUniqueCaptureNs = frame.timestampNs
                                lastObserveNs = nowNano
                                val timeFromHoldStartMs = (nowNano - trace.holdStartNs) / 1_000_000L
                                Log.d(TAG, "GREEN_STABILIZING: shotId=$shotId, green=${analysis.greenLeft}..${analysis.greenRight}, stableCount=$greenStableCount, cursor=${analysis.cursorX}, holdStart=${timeFromHoldStartMs}ms")
                                delay(8L)
                                continue
                            }

                            // Armed: cursor in left quarter + green stable for 2 frames
                            armed = true
                            lastUniqueX = analysis.cursorX
                            lastUniqueCaptureNs = frame.timestampNs
                            lastObserveNs = nowNano
                            val timeFromHoldStartMs = (nowNano - trace.holdStartNs) / 1_000_000L
                            Log.d(TAG, "DETECTED: cursor=${analysis.cursorX}, green=${analysis.greenLeft}..${analysis.greenRight}, holdStart=${timeFromHoldStartMs}ms, frame #$frameCount, frameSeq=${frame.frameSeq}")
                        }

                        // Update target every frame — aim at green zone LEFT edge
                        if (analysis.greenLeft != targetX) {
                            targetX = analysis.greenLeft
                        }
                        trace.lastGreenLeft = analysis.greenLeft
                        trace.lastGreenRight = analysis.greenRight

                        val remaining = targetX - analysis.cursorX

                        // Update speed from unique cursor transitions (CAPTURE-domain timing)
                        if (analysis.cursorX != lastUniqueX && lastUniqueX >= 0) {
                            val stepDx = analysis.cursorX - lastUniqueX
                            val captureDtNs = frame.timestampNs - lastUniqueCaptureNs
                            val observeDtNs = nowNano - lastObserveNs
                            val captureLagMs = (nowNano - frame.timestampNs) / 1_000_000f
                            val captureDtMs = captureDtNs / 1_000_000f

                            if (captureDtNs > 0 && stepDx > 0) {
                                recentSteps.add(Pair(stepDx, captureDtNs))
                                if (recentSteps.size > SPEED_WINDOW) recentSteps.removeAt(0)
                                speedCount = recentSteps.size
                                val totalDist = recentSteps.sumOf { it.first }
                                val totalTimeNs = recentSteps.sumOf { it.second }
                                smoothSpeed = totalDist.toFloat() / (totalTimeNs.toFloat() / 1_000_000f)
                                val stepSpeed = stepDx.toFloat() / captureDtMs
                                Log.d(TAG, "FRAME_TRANSITION: shotId=$shotId, frameSeq=${frame.frameSeq}, frameTsNs=${frame.timestampNs}, observeNs=$nowNano, captureLagMs=${String.format("%.1f", captureLagMs)}, cursorX=${analysis.cursorX}, greenLeft=${analysis.greenLeft}, greenRight=${analysis.greenRight}, targetX=$targetX, frameDtMs=${String.format("%.1f", captureDtMs)}, observeDtMs=${String.format("%.1f", observeDtNs / 1_000_000f)}, stepSpeed=${String.format("%.3f", stepSpeed)}px/ms, smoothSpeed=${String.format("%.3f", smoothSpeed)}px/ms (#$speedCount)")
                            }

                            // Feed PLL with CAPTURE-domain timestamp
                            frameClock.recordFrameTransition(frame.timestampNs)

                            lastUniqueX = analysis.cursorX
                            lastUniqueCaptureNs = frame.timestampNs
                            lastObserveNs = nowNano
                        }

                        if (remaining <= 0) {
                            trace.decisionNs = nowNano
                            trace.decisionFrameSeq = frame.frameSeq
                            trace.decisionFrameTsNs = frame.timestampNs
                            trace.decisionCursorX = analysis.cursorX
                            trace.decisionTargetX = targetX
                            trace.decisionGreenLeft = analysis.greenLeft
                            trace.decisionGreenRight = analysis.greenRight

                            Log.d(TAG, "RELEASE_DECIDE: shotId=$shotId, decisionNs=$nowNano, decisionFrameSeq=${frame.frameSeq}, decisionFrameTsNs=${frame.timestampNs}, decisionCursorX=${analysis.cursorX}, targetX=$targetX, remainingPx=$remaining, speedPxMs=${String.format("%.3f", smoothSpeed)}, fixedLatencyMs=${releaseLatencyMs}, decisionGreen=${analysis.greenLeft}..${analysis.greenRight}, policy=cursor_past_target")

                            val stamp = touchInjector.release()
                            trace.sendIntentNs = stamp.sendIntentNs
                            trace.sendFlushDoneNs = stamp.sendFlushDoneNs
                            trace.sendPath = stamp.path

                            val decisionFrameAgeMs = (stamp.sendFlushDoneNs - trace.decisionFrameTsNs) / 1_000_000f
                            val decisionToSendMs = (stamp.sendFlushDoneNs - trace.decisionNs) / 1_000_000f
                            Log.d(TAG, "RELEASE_SEND: shotId=$shotId, sendIntentNs=${stamp.sendIntentNs}, sendFlushDoneNs=${stamp.sendFlushDoneNs}, sendOverheadMs=${String.format("%.2f", (stamp.sendFlushDoneNs - stamp.sendIntentNs) / 1_000_000f)}, path=${stamp.path}, decisionFrameAgeMs=${String.format("%.1f", decisionFrameAgeMs)}, decisionToSendMs=${String.format("%.1f", decisionToSendMs)}")

                            runPostReleasePoll(shotId, trace, releaseCursorX = analysis.cursorX, releaseTargetX = targetX, dupCount, detector)
                            isRunning = false
                            if (isActive) onResult(true)
                            return@launch
                        }

                        if (speedCount >= 3) {
                            val timeToTargetMs = if (smoothSpeed > 0) (remaining / smoothSpeed).toLong() else Long.MAX_VALUE

                            // Build phase plan from capture-domain boundary + clock bridge
                            val plan = buildPhasePlan(frame, nowNano, remaining, smoothSpeed)

                            if (remaining < 80) {
                                val pllStatus = if (frameClock.isLocked) "LOCKED" else "unlocked"
                                val planWaitMs = if (plan != null) String.format("%.1f", plan.plannedWaitNs / 1_000_000f) else "n/a"
                                val planPolicy = plan?.policy ?: "no_plan"
                                Log.d(TAG, "APPROACH: shotId=$shotId, cursor=${analysis.cursorX}, speed=${String.format("%.3f", smoothSpeed)}px/ms, remaining=${remaining}px, ttt=${timeToTargetMs}ms, PLL=$pllStatus, planWait=${planWaitMs}ms, policy=$planPolicy")
                            }

                            // Release condition: use effective latency from plan if available
                            val effectiveLatencyMs = if (plan != null && plan.policy == "boundary_aligned") {
                                plan.effectiveLatencyNs / 1_000_000f
                            } else {
                                releaseLatencyMs.toFloat()
                            }

                            if (remaining <= smoothSpeed * effectiveLatencyMs) {
                                val releaseCursorX = analysis.cursorX
                                val releaseTargetX = targetX

                                // Record decision + decision-time green zone
                                trace.decisionNs = nowNano
                                trace.decisionFrameSeq = frame.frameSeq
                                trace.decisionFrameTsNs = frame.timestampNs
                                trace.decisionCursorX = analysis.cursorX
                                trace.decisionTargetX = targetX
                                trace.decisionSpeedPxMs = smoothSpeed
                                trace.decisionGreenLeft = analysis.greenLeft
                                trace.decisionGreenRight = analysis.greenRight

                                // Log phase plan
                                if (plan != null) {
                                    trace.boundaryCaptureNs = plan.boundaryCaptureNs
                                    trace.boundarySystemNs = plan.boundarySystemNs
                                    trace.targetSendIntentNs = plan.targetSendIntentNs
                                    trace.targetSendFlushNs = plan.targetSendFlushNs
                                    trace.plannedWaitMs = plan.plannedWaitNs / 1_000_000f
                                    trace.effectiveLatencyMs = plan.effectiveLatencyNs / 1_000_000f
                                    trace.phasePolicy = plan.policy

                                    Log.d(TAG, "PHASE_PLAN: shotId=$shotId, decisionNs=$nowNano, decisionFrameTsNs=${frame.timestampNs}, boundaryCaptureNs=${plan.boundaryCaptureNs}, boundarySystemNs=${plan.boundarySystemNs}, targetSendIntentNs=${plan.targetSendIntentNs}, targetSendFlushNs=${plan.targetSendFlushNs}, plannedWaitMs=${String.format("%.1f", plan.plannedWaitNs / 1_000_000f)}, effectiveLatencyMs=${String.format("%.1f", plan.effectiveLatencyNs / 1_000_000f)}, bridgeOffsetMs=${String.format("%.1f", plan.bridgeOffsetNs / 1_000_000f)}, bridgeJitterMs=${String.format("%.2f", plan.bridgeJitterNs / 1_000_000f)}, expectedSendOverheadMs=${String.format("%.1f", expectedSendOverheadNs / 1_000_000f)}, policy=${plan.policy}, mode=$phaseMode")

                                    if (plan.policy == "boundary_aligned") {
                                        trace.targetMode = "phaseAligned"
                                    }
                                }

                                Log.d(TAG, "RELEASE_DECIDE: shotId=$shotId, decisionNs=$nowNano, decisionFrameSeq=${frame.frameSeq}, decisionFrameTsNs=${frame.timestampNs}, decisionCursorX=${analysis.cursorX}, targetX=$targetX, remainingPx=$remaining, speedPxMs=${String.format("%.3f", smoothSpeed)}, effectiveLatencyMs=${String.format("%.1f", effectiveLatencyMs)}, fixedLatencyMs=${releaseLatencyMs}, decisionGreen=${analysis.greenLeft}..${analysis.greenRight}, policy=${plan?.policy ?: "baseline"}")

                                // Phase-aligned sleep in LIVE mode
                                if (phaseMode == PhaseAlignMode.LIVE && plan != null && plan.policy == "boundary_aligned" && plan.plannedWaitNs > 1_000_000L) {
                                    val sleepTrace = absoluteSleeper.sleepUntil(plan.targetSendIntentNs)
                                    trace.wakeNs = sleepTrace.wakeNs
                                    trace.wakeErrorNs = sleepTrace.wakeErrorNs
                                    Log.d(TAG, "SLEEP_TRACE: shotId=$shotId, targetNs=${sleepTrace.targetNs}, coarseWakeNs=${sleepTrace.coarseWakeNs}, spinStartNs=${sleepTrace.spinStartNs}, wakeNs=${sleepTrace.wakeNs}, wakeErrorUs=${String.format("%.0f", sleepTrace.wakeErrorNs / 1_000f)}")
                                }

                                val stamp = touchInjector.release()
                                trace.sendIntentNs = stamp.sendIntentNs
                                trace.sendFlushDoneNs = stamp.sendFlushDoneNs
                                trace.sendPath = stamp.path

                                val decisionFrameAgeMs = (stamp.sendFlushDoneNs - trace.decisionFrameTsNs) / 1_000_000f
                                val decisionToSendMs = (stamp.sendFlushDoneNs - trace.decisionNs) / 1_000_000f
                                val intentErrorUs = if (trace.targetSendIntentNs > 0) (stamp.sendIntentNs - trace.targetSendIntentNs) / 1_000f else 0f
                                val flushErrorUs = if (trace.targetSendFlushNs > 0) (stamp.sendFlushDoneNs - trace.targetSendFlushNs) / 1_000f else 0f
                                Log.d(TAG, "RELEASE_SEND: shotId=$shotId, sendIntentNs=${stamp.sendIntentNs}, sendFlushDoneNs=${stamp.sendFlushDoneNs}, sendOverheadMs=${String.format("%.2f", (stamp.sendFlushDoneNs - stamp.sendIntentNs) / 1_000_000f)}, path=${stamp.path}, decisionFrameAgeMs=${String.format("%.1f", decisionFrameAgeMs)}, decisionToSendMs=${String.format("%.1f", decisionToSendMs)}, targetSendIntentNs=${trace.targetSendIntentNs}, targetSendFlushNs=${trace.targetSendFlushNs}, intentErrorUs=${String.format("%.0f", intentErrorUs)}, flushErrorUs=${String.format("%.0f", flushErrorUs)}")

                                runPostReleasePoll(shotId, trace, releaseCursorX, releaseTargetX, dupCount, detector)
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

            Log.d(TAG, "SHOT_END: shotId=$shotId, result=TIMEOUT")
            touchInjector.release()
            isRunning = false
            if (isActive) onResult(false)
        }
    }

    /**
     * Build a phase-aligned release plan from the PLL boundary + clock bridge.
     * Returns null if PLL is not locked or clock bridge has insufficient data.
     */
    private fun buildPhasePlan(
        frame: BarFrame,
        nowNs: Long,
        remainingPx: Int,
        speedPxMs: Float
    ): PhasePlan? {
        if (!frameClock.isLocked) return null
        val bridge = clockBridge.snapshot() ?: return null

        // Next frame boundary in capture domain
        val boundaryCaptureNs = frameClock.nextBoundaryAfter(frame.timestampNs)

        // Convert to system time using bridge offset
        val boundarySystemNs = boundaryCaptureNs + bridge.offsetNs

        // Target: flush completes right at boundary + phaseOffset
        val targetSendFlushNs = boundarySystemNs + phaseOffsetNs

        // Intent happens before flush by expected send overhead
        val targetSendIntentNs = targetSendFlushNs - expectedSendOverheadNs

        // How long to wait from now
        val plannedWaitNs = targetSendIntentNs - nowNs

        // Policy: skip if boundary already passed or wait too long
        val policy: String
        if (plannedWaitNs < -expectedSendOverheadNs) {
            // Boundary already passed by more than send overhead — no point waiting
            policy = "boundary_passed"
        } else if (plannedWaitNs > frameClock.estimatedPeriodNs) {
            // Wait would be longer than one frame period — prediction is off
            policy = "wait_too_long"
        } else if (plannedWaitNs < 0) {
            // Slightly negative — boundary just passed but within send overhead
            // We can still target it; the send will just be slightly late
            policy = "boundary_just_passed"
        } else {
            policy = "boundary_aligned"
        }

        // Effective latency: planned wait (clamped to 0) + baseline latency
        val effectiveLatencyNs = plannedWaitNs.coerceAtLeast(0) + releaseLatencyMs * 1_000_000L

        return PhasePlan(
            boundaryCaptureNs = boundaryCaptureNs,
            boundarySystemNs = boundarySystemNs,
            targetSendFlushNs = targetSendFlushNs,
            targetSendIntentNs = targetSendIntentNs,
            plannedWaitNs = plannedWaitNs,
            effectiveLatencyNs = effectiveLatencyNs,
            bridgeOffsetNs = bridge.offsetNs,
            bridgeJitterNs = bridge.jitterNs,
            policy = policy
        )
    }

    /**
     * Post-release polling loop: find first post-send frame, then detect cursor stop.
     * Uses frameTsNs > sendFlushDoneNs as minimum gate to exclude in-flight frames.
     */
    private suspend fun runPostReleasePoll(
        shotId: Int,
        trace: ShotTrace,
        releaseCursorX: Int,
        releaseTargetX: Int,
        dupCount: Int,
        detector: GreenZoneDetector
    ) {
        val sendFlushDoneNs = trace.sendFlushDoneNs
        var firstPostFound = false
        var stableCount = 0
        var lastPostCursorX = -1
        var lastPostTsNs = 0L
        var lastPostGreenLeft = 0
        var lastPostGreenRight = 0
        var pollCount = 0
        val maxPolls = 50  // ~400ms at 8ms per poll

        while (pollCount < maxPolls) {
            delay(8L)
            pollCount++
            val relFrame = frameProvider()
            if (relFrame == null || relFrame.isDuplicate) continue

            // Gate: only accept frames whose CAPTURE timestamp is after send completed.
            if (relFrame.timestampNs <= sendFlushDoneNs) {
                val relAnalysis = detector.analyzeBar(relFrame.width, relFrame.height, relFrame.getPixel)
                if (relAnalysis.hasCursor) {
                    Log.d(TAG, "IN_FLIGHT_FRAME: shotId=$shotId, frameSeq=${relFrame.frameSeq}, frameTsNs=${relFrame.timestampNs}, observeNs=${relFrame.frameObservedNs}, cursorX=${relAnalysis.cursorX} (captured before send)")
                }
                continue
            }

            val relAnalysis = detector.analyzeBar(relFrame.width, relFrame.height, relFrame.getPixel)

            if (!firstPostFound && relAnalysis.hasCursor) {
                firstPostFound = true
                trace.firstPostFrameSeq = relFrame.frameSeq
                trace.firstPostFrameTsNs = relFrame.timestampNs
                trace.firstPostCursorX = relAnalysis.cursorX
                Log.d(TAG, "POST_RELEASE_FIRST_FRAME: shotId=$shotId, frameSeq=${relFrame.frameSeq}, frameTsNs=${relFrame.timestampNs}, observeNs=${relFrame.frameObservedNs}, cursorX=${relAnalysis.cursorX}, greenLeft=${relAnalysis.greenLeft}, greenRight=${relAnalysis.greenRight}")
            }

            // Detect stop: cursor stable for 2 unique frames or cursor disappears
            if (firstPostFound) {
                if (relAnalysis.hasCursor) {
                    if (relAnalysis.cursorX == lastPostCursorX) {
                        stableCount++
                        if (stableCount >= 2) {
                            emitStop(shotId, trace, relFrame.frameSeq, relFrame.timestampNs,
                                relAnalysis.cursorX, relAnalysis.greenLeft, relAnalysis.greenRight,
                                releaseCursorX, releaseTargetX, dupCount)
                            return
                        }
                    } else {
                        stableCount = 0
                    }
                    lastPostCursorX = relAnalysis.cursorX
                    lastPostTsNs = relFrame.timestampNs
                    lastPostGreenLeft = relAnalysis.greenLeft
                    lastPostGreenRight = relAnalysis.greenRight
                } else {
                    // Cursor disappeared — bar gone, shot is over
                    if (lastPostCursorX >= 0) {
                        emitStop(shotId, trace, relFrame.frameSeq, lastPostTsNs,
                            lastPostCursorX, lastPostGreenLeft, lastPostGreenRight,
                            releaseCursorX, releaseTargetX, dupCount,
                            cursorGone = true)
                    }
                    return
                }
            }
        }

        // Exhausted polls without stable stop
        trace.result = "POLL_EXPIRED"
        Log.d(TAG, "SHOT_END: shotId=$shotId, result=${trace.result}, inGreen=false, finalCursorX=$lastPostCursorX, decisionCursorX=${trace.decisionCursorX}, targetX=${trace.decisionTargetX}")
    }

    /**
     * Emit stop frame and final SHOT_END verdict.
     * Scores against decision-time green zone if stop-frame green zone is missing.
     */
    private fun emitStop(
        shotId: Int, trace: ShotTrace,
        stopFrameSeq: Long, stopFrameTsNs: Long, stopCursorX: Int,
        stopGreenLeft: Int, stopGreenRight: Int,
        releaseCursorX: Int, releaseTargetX: Int,
        dupCount: Int,
        cursorGone: Boolean = false
    ) {
        trace.stopFrameSeq = stopFrameSeq
        trace.stopFrameTsNs = stopFrameTsNs
        trace.stopCursorX = stopCursorX

        val sendFlushDoneNs = trace.sendFlushDoneNs
        val decisionToStopCaptureMs = (stopFrameTsNs - trace.decisionFrameTsNs) / 1_000_000f
        val decisionToStopFrames = stopFrameSeq - trace.decisionFrameSeq
        val sendToStopCaptureMs = (stopFrameTsNs - sendFlushDoneNs) / 1_000_000f
        val travelPx = stopCursorX - releaseCursorX

        // Score against decision-time green zone when stop-frame green is missing
        val scoringGreenLeft: Int
        val scoringGreenRight: Int
        val scoringSource: String
        if (stopGreenLeft > 0 && stopGreenRight > stopGreenLeft) {
            scoringGreenLeft = stopGreenLeft
            scoringGreenRight = stopGreenRight
            scoringSource = "stopFrame"
        } else {
            scoringGreenLeft = trace.decisionGreenLeft
            scoringGreenRight = trace.decisionGreenRight
            scoringSource = "decisionFrame"
        }

        val stopPastDecisionZonePx = stopCursorX - scoringGreenRight
        val inGreen = stopCursorX in scoringGreenLeft..scoringGreenRight
        val verdict = when {
            inGreen -> "IN_GREEN"
            stopCursorX < releaseTargetX -> "EARLY"
            else -> "LATE"
        }
        trace.result = verdict

        val goneLabel = if (cursorGone) " (cursor gone)" else ""
        Log.d(TAG, "POST_RELEASE_STOP: shotId=$shotId, stopFrameSeq=$stopFrameSeq, stopFrameTsNs=$stopFrameTsNs, stopCursorX=$stopCursorX$goneLabel, travelPx=$travelPx, decisionToStopFrames=$decisionToStopFrames, decisionToStopCaptureMs=${String.format("%.1f", decisionToStopCaptureMs)}, sendToStopCaptureMs=${String.format("%.1f", sendToStopCaptureMs)}, stopPastDecisionZonePx=$stopPastDecisionZonePx, scoringGreen=$scoringSource(${scoringGreenLeft}..${scoringGreenRight})")

        Log.d(TAG, "SHOT_END: shotId=$shotId, result=$verdict, inGreen=$inGreen, finalCursorX=$stopCursorX, decisionCursorX=${trace.decisionCursorX}, targetX=${trace.decisionTargetX}, phaseWaitMs=${String.format("%.1f", trace.plannedWaitMs)}, effectiveLatencyMs=${String.format("%.1f", trace.effectiveLatencyMs)}, mode=${trace.targetMode}, policy=${trace.phasePolicy}, decisionGreen=${trace.decisionGreenLeft}..${trace.decisionGreenRight}, stopGreen=${stopGreenLeft}..${stopGreenRight}, scoringGreen=$scoringSource")
        if (dupCount > 0) {
            Log.d(TAG, "Frame dedup: shotId=$shotId, skipped $dupCount duplicate frames")
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
