package com.denusklo.hooplandhelper.core

import android.util.Log
import com.denusklo.hooplandhelper.data.CalibrationRepository
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
    val policy: String,
    val estimatedNowCaptureNs: Long
)

private data class BoundaryBlendEstimate(
    val learnedFastCoastMs: Float,
    val liveFastCoastMs: Float,
    val learnedSlowCoastMs: Float,
    val rawBlendedBoundaryCoastMs: Float,
    val estimatedPeriodMs: Float,
    val frameRatio: Float,
    val quantizedFrames: Float,
    val rawQuantizedBoundaryCoastMs: Float,
    val correctionMs: Float,
    val correctedBoundaryCoastMs: Float,
    val slowSamples: Int,
    val slowBlendActive: Boolean
)

private data class BoundaryConfidenceGate(
    val baAllowed: Boolean,
    val reason: String,
    val warning: String,
    val pllStddevMs: Float,
    val pllStable: Boolean,
    val nearFrameBoundary: Boolean,
    val fastFloorActive: Boolean,
    val fallbackPolicy: String
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
    var phaseSleepApplied: Boolean = false,
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
    var boundaryDecisionIndex: Int = 0,
    var boundaryTimeSincePreviousShotMsLabel: String = "n/a",
    var boundaryTimeSincePreviousBoundaryShotMsLabel: String = "n/a",
    var boundaryPreviousBucket: String = "none",
    var boundaryRecentBucketsBefore: String = "[]",
    var boundaryRecentSendToStopMsBefore: String = "[]",
    var boundaryBucketShadowPrediction: String = "",
    var result: String = ""
)

class ShotManager(
    private val touchInjector: TouchInjector,
    private val calibration: CalibrationRepository,
    private val frameProvider: FrameProvider,
    private val timeoutMs: Long = 3000L,
    initialLatencyMs: Long = 204L,
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
    private val boundaryAlignedCoastHistoryMs = ArrayDeque<Float>()
    private val boundaryAlignedFastCoastHistoryMs = ArrayDeque<Float>()
    private val boundaryAlignedSlowCoastHistoryMs = ArrayDeque<Float>()
    private val recentBoundaryAlignedRealizedBuckets = ArrayDeque<String>()
    private val recentBoundaryAlignedRealizedSendToStopMs = ArrayDeque<Float>()
    private val waitTooLongCoastHistoryMs = ArrayDeque<Float>()
    private val boundaryAlignedCoastWindowSize = 9
    private val boundaryAlignedShadowHistorySize = 3
    private val waitTooLongCoastWindowSize = 5
    private val boundaryAlignedSlowRisk = 0.20f
    private val boundaryAlignedFastCoastMinMs = 120f
    private val boundaryAlignedMinSlowSamplesForBlend = 2
    private val boundaryAlignedPostQuantizationCorrectionFrames = 0.5f
    private val boundaryAlignedFrameBoundaryGuard = 0.08f
    private val boundaryAlignedMaxPllStddevMs = 2.5f
    private val waitTooLongCoastMaxMs = 95f

    // Phase offset: positive delays target flush later relative to predicted boundary.
    // Offset tuning step for early-bias correction. Current: +58ms.
    private val phaseOffsetNs = 58_000_000L

    // Phase alignment mode: SHADOW = log plan but send immediately, LIVE = use absoluteSleeper
    private val phaseMode = PhaseAlignMode.LIVE

    // Monotonic shot counter for log correlation
    private var shotCounter = 0
    private var boundaryDecisionCounter = 0
    private var lastShotEndNs = 0L
    private var lastBoundaryShotEndNs = 0L

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
            var armed = false  // false-start guard: first accepted green must appear in left quarter
            var targetX = 0
            var frameCount = 0
            var dupCount = 0

            // Speed tracking: sliding window of recent cursor transitions
            // Uses CAPTURE-domain timestamps (frame.timestampNs) for cadence
            var lastUniqueX = -1
            var lastUniqueCaptureNs = 0L
            var lastObserveNs = 0L
            var speedCount = 0
            var smoothSpeed = 0f
            val recentSteps = mutableListOf<Pair<Int, Long>>() // (dx, capture_dt_ns)
            val recentPllTransitionTimestampsNs = mutableListOf<Long>()
            val recentPllPeriodEstimatesNs = mutableListOf<Long>()
            var currentPllStddevMs = Float.POSITIVE_INFINITY
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

                            // Detector-returned green is already stabilized; arm immediately.
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
                            currentPllStddevMs = recordLocalPllPeriodStddevMs(
                                recentPllTransitionTimestampsNs,
                                recentPllPeriodEstimatesNs,
                                frame.timestampNs
                            )

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
                            val plannedWaitMsForPolicy = plan?.plannedWaitNs?.coerceAtLeast(0L)?.div(1_000_000f) ?: 0f
                            val boundaryBlendEstimate = when (plan?.policy) {
                                "boundary_aligned" -> getLiveBoundaryBlendEstimate()
                                else -> null
                            }
                            val boundaryConfidenceGate =
                                boundaryBlendEstimate?.let {
                                    evaluateBoundaryConfidenceGate(it, currentPllStddevMs)
                                }
                            if (boundaryConfidenceGate != null) {
                                val estimate = boundaryBlendEstimate
                                Log.d(
                                    TAG,
                                    "BOUNDARY_CONFIDENCE_GATE: shotId=$shotId, baAllowed=${boundaryConfidenceGate.baAllowed}, reason=${boundaryConfidenceGate.reason}, warning=${boundaryConfidenceGate.warning}, pllStddevMs=${formatOptionalMs(boundaryConfidenceGate.pllStddevMs)}, pllStable=${boundaryConfidenceGate.pllStable}, frameRatio=${String.format("%.2f", estimate.frameRatio)}, nearFrameBoundary=${boundaryConfidenceGate.nearFrameBoundary}, learnedFastCoastMs=${String.format("%.1f", estimate.learnedFastCoastMs)}, liveFastCoastMs=${String.format("%.1f", estimate.liveFastCoastMs)}, fastFloorActive=${boundaryConfidenceGate.fastFloorActive}, fallbackPolicy=${boundaryConfidenceGate.fallbackPolicy}"
                                )
                            }
                            val livePolicy =
                                if (plan?.policy == "boundary_aligned" && boundaryConfidenceGate?.baAllowed == false) {
                                    "wait_too_long"
                                } else {
                                    plan?.policy
                                }
                            val rawLearnedWaitTooLongCoastMs = when (livePolicy) {
                                "wait_too_long" -> getLearnedWaitTooLongCoastMs()
                                else -> null
                            }
                            val liveWaitTooLongCoastMs = rawLearnedWaitTooLongCoastMs?.coerceAtMost(waitTooLongCoastMaxMs)
                            val learnedCoastMs = when (livePolicy) {
                                "boundary_aligned" -> boundaryBlendEstimate!!.correctedBoundaryCoastMs
                                "wait_too_long" -> liveWaitTooLongCoastMs!!
                                else -> null
                            }
                            val effectiveLatencyMs = when (livePolicy) {
                                "boundary_aligned" -> plannedWaitMsForPolicy + learnedCoastMs!!
                                "wait_too_long" -> learnedCoastMs!!
                                else -> releaseLatencyMs.toFloat()
                            }

                            if (plan != null && learnedCoastMs != null) {
                                Log.d(
                                    TAG,
                                    "LATENCY_MODEL_USE: policy=$livePolicy, plannedWaitMs=${String.format("%.1f", plannedWaitMsForPolicy)}, learnedCoastMs=${String.format("%.1f", learnedCoastMs)}, effectiveLatencyMs=${String.format("%.1f", effectiveLatencyMs)}"
                                )
                            }
                            if (livePolicy == "wait_too_long" && rawLearnedWaitTooLongCoastMs != null && liveWaitTooLongCoastMs != null) {
                                Log.d(
                                    TAG,
                                    "WAIT_TOO_LONG_CLAMP_USE: shotId=$shotId, rawLearnedCoastMs=${String.format("%.1f", rawLearnedWaitTooLongCoastMs)}, clampedCoastMs=${String.format("%.1f", liveWaitTooLongCoastMs)}, capMs=${String.format("%.1f", waitTooLongCoastMaxMs)}, capApplied=${rawLearnedWaitTooLongCoastMs > liveWaitTooLongCoastMs}, plannedWaitMs=${String.format("%.1f", plannedWaitMsForPolicy)}, effectiveLatencyMs=${String.format("%.1f", effectiveLatencyMs)}"
                                )
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
                                    trace.effectiveLatencyMs = effectiveLatencyMs
                                    trace.phasePolicy = livePolicy ?: plan.policy

                                    Log.d(TAG, "PHASE_PLAN: shotId=$shotId, decisionNs=$nowNano, decisionFrameTsNs=${frame.timestampNs}, estimatedNowCaptureNs=${plan.estimatedNowCaptureNs}, boundaryCaptureNs=${plan.boundaryCaptureNs}, boundarySystemNs=${plan.boundarySystemNs}, targetSendIntentNs=${plan.targetSendIntentNs}, targetSendFlushNs=${plan.targetSendFlushNs}, plannedWaitMs=${String.format("%.1f", plan.plannedWaitNs / 1_000_000f)}, effectiveLatencyMs=${String.format("%.1f", effectiveLatencyMs)}, bridgeOffsetMs=${String.format("%.1f", plan.bridgeOffsetNs / 1_000_000f)}, bridgeJitterMs=${String.format("%.2f", plan.bridgeJitterNs / 1_000_000f)}, expectedSendOverheadMs=${String.format("%.1f", expectedSendOverheadNs / 1_000_000f)}, policy=$livePolicy, phaseMode=$phaseMode")

                                    if (plan.policy == "boundary_aligned") {
                                        if (livePolicy == "boundary_aligned") {
                                            trace.targetMode = "phaseAligned"
                                        }
                                        trace.boundaryDecisionIndex = ++boundaryDecisionCounter
                                        trace.boundaryTimeSincePreviousShotMsLabel =
                                            formatElapsedSinceMs(lastShotEndNs, trace.decisionNs)
                                        trace.boundaryTimeSincePreviousBoundaryShotMsLabel =
                                            formatElapsedSinceMs(lastBoundaryShotEndNs, trace.decisionNs)
                                        trace.boundaryPreviousBucket =
                                            recentBoundaryAlignedRealizedBuckets.lastOrNull() ?: "none"
                                        trace.boundaryRecentBucketsBefore =
                                            formatBoundaryBucketHistory(recentBoundaryAlignedRealizedBuckets)
                                        trace.boundaryRecentSendToStopMsBefore =
                                            formatBoundarySendToStopHistory(recentBoundaryAlignedRealizedSendToStopMs)
                                        trace.boundaryBucketShadowPrediction =
                                            predictBoundaryAlignedBucketFromRecentHistory()
                                        Log.d(
                                            TAG,
                                            "BOUNDARY_BUCKET_SHADOW_USE: shotId=$shotId, predictedBucket=${trace.boundaryBucketShadowPrediction}, recentBuckets=${formatBoundaryBucketHistory(recentBoundaryAlignedRealizedBuckets)}, learnedFastCoastMs=${formatLearnedCoastMs(getLearnedBoundaryAlignedFastCoastMsOrNull())}, learnedSlowCoastMs=${formatLearnedCoastMs(getLearnedBoundaryAlignedSlowCoastMsOrNull())}"
                                        )
                                        Log.d(
                                            TAG,
                                            "BOUNDARY_SESSION_STATE: shotId=$shotId, sessionShotIndex=$shotId, boundaryDecisionIndex=${trace.boundaryDecisionIndex}, timeSincePreviousShotMs=${trace.boundaryTimeSincePreviousShotMsLabel}, timeSincePreviousBoundaryShotMs=${trace.boundaryTimeSincePreviousBoundaryShotMsLabel}, previousBoundaryBucket=${trace.boundaryPreviousBucket}, recentBoundaryBuckets=${trace.boundaryRecentBucketsBefore}, recentBoundarySendToStopMs=${trace.boundaryRecentSendToStopMsBefore}, learnedFastCoastMs=${formatLearnedCoastMs(getLearnedBoundaryAlignedFastCoastMsOrNull())}, learnedSlowCoastMs=${formatLearnedCoastMs(getLearnedBoundaryAlignedSlowCoastMsOrNull())}"
                                        )
                                        if (boundaryBlendEstimate != null && livePolicy == "boundary_aligned") {
                                            Log.d(
                                                TAG,
                                                "BOUNDARY_BLEND_USE: shotId=$shotId, learnedFastCoastMs=${String.format("%.1f", boundaryBlendEstimate.learnedFastCoastMs)}, liveFastCoastMs=${String.format("%.1f", boundaryBlendEstimate.liveFastCoastMs)}, learnedSlowCoastMs=${String.format("%.1f", boundaryBlendEstimate.learnedSlowCoastMs)}, slowSamples=${boundaryBlendEstimate.slowSamples}, slowBlendActive=${boundaryBlendEstimate.slowBlendActive}, minSlowSamples=${boundaryAlignedMinSlowSamplesForBlend}, slowRisk=${String.format("%.2f", boundaryAlignedSlowRisk)}, quantizer=half_frame_3_3.5_4, estimatedPeriodMs=${String.format("%.2f", boundaryBlendEstimate.estimatedPeriodMs)}, rawBlendedBoundaryCoastMs=${String.format("%.1f", boundaryBlendEstimate.rawBlendedBoundaryCoastMs)}, frameRatio=${String.format("%.2f", boundaryBlendEstimate.frameRatio)}, quantizedFrames=${String.format("%.1f", boundaryBlendEstimate.quantizedFrames)}, rawQuantizedBoundaryCoastMs=${String.format("%.1f", boundaryBlendEstimate.rawQuantizedBoundaryCoastMs)}, correctionMs=${String.format("%.1f", boundaryBlendEstimate.correctionMs)}, correctedBoundaryCoastMs=${String.format("%.1f", boundaryBlendEstimate.correctedBoundaryCoastMs)}, plannedWaitMs=${String.format("%.1f", plannedWaitMsForPolicy)}, effectiveLatencyMs=${String.format("%.1f", effectiveLatencyMs)}"
                                            )
                                        }
                                    }
                                }

                                Log.d(TAG, "RELEASE_DECIDE: shotId=$shotId, decisionNs=$nowNano, decisionFrameSeq=${frame.frameSeq}, decisionFrameTsNs=${frame.timestampNs}, decisionCursorX=${analysis.cursorX}, targetX=$targetX, remainingPx=$remaining, speedPxMs=${String.format("%.3f", smoothSpeed)}, effectiveLatencyMs=${String.format("%.1f", effectiveLatencyMs)}, fixedLatencyMs=${releaseLatencyMs}, decisionGreen=${analysis.greenLeft}..${analysis.greenRight}, policy=${livePolicy ?: "baseline"}")

                                // Phase-aligned sleep in LIVE mode
                                if (phaseMode == PhaseAlignMode.LIVE && plan != null && livePolicy == "boundary_aligned" && plan.plannedWaitNs > 1_000_000L) {
                                    val sleepTrace = absoluteSleeper.sleepUntil(plan.targetSendIntentNs)
                                    trace.wakeNs = sleepTrace.wakeNs
                                    trace.wakeErrorNs = sleepTrace.wakeErrorNs
                                    trace.phaseSleepApplied = true
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
                                val sendOverheadMs = (stamp.sendFlushDoneNs - stamp.sendIntentNs) / 1_000_000f
                                Log.d(TAG, "RELEASE_SEND: shotId=$shotId, sendIntentNs=${stamp.sendIntentNs}, sendFlushDoneNs=${stamp.sendFlushDoneNs}, sendOverheadMs=${String.format("%.2f", (stamp.sendFlushDoneNs - stamp.sendIntentNs) / 1_000_000f)}, path=${stamp.path}, decisionFrameAgeMs=${String.format("%.1f", decisionFrameAgeMs)}, decisionToSendMs=${String.format("%.1f", decisionToSendMs)}, targetSendIntentNs=${trace.targetSendIntentNs}, targetSendFlushNs=${trace.targetSendFlushNs}, intentErrorUs=${String.format("%.0f", intentErrorUs)}, flushErrorUs=${String.format("%.0f", flushErrorUs)}, phaseSleepApplied=${trace.phaseSleepApplied}")
                                if (plan != null && plan.policy == "boundary_aligned") {
                                    val wakeErrorUsLabel =
                                        if (trace.phaseSleepApplied) {
                                            String.format("%.0f", trace.wakeErrorNs / 1_000f)
                                        } else {
                                            "n/a"
                                        }
                                    Log.d(
                                        TAG,
                                        "BOUNDARY_BUCKET_FEATURES: shotId=$shotId, plannedWaitMs=${String.format("%.1f", plannedWaitMsForPolicy)}, bridgeJitterMs=${String.format("%.2f", plan.bridgeJitterNs / 1_000_000f)}, wakeErrorUs=$wakeErrorUsLabel, intentErrorUs=${String.format("%.0f", intentErrorUs)}, flushErrorUs=${String.format("%.0f", flushErrorUs)}, sendOverheadMs=${String.format("%.2f", sendOverheadMs)}, decisionFrameAgeMs=${String.format("%.1f", decisionFrameAgeMs)}, speedPxMs=${String.format("%.3f", smoothSpeed)}, remainingPx=$remaining"
                                    )
                                }

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
            recordShotEndSessionState(trace, System.nanoTime())
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

        // Estimate current capture-domain time (frame.timestampNs is stale by processing delay)
        val estimatedNowCaptureNs = nowNs - bridge.offsetNs

        // Next frame boundary in capture domain, predicted from estimated current capture time
        val boundaryCaptureNs = frameClock.nextBoundaryAfter(estimatedNowCaptureNs)

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
        } else if (plannedWaitNs > frameClock.estimatedPeriodNs * 2) {
            // Wait longer than two frame periods — prediction is off
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
            policy = policy,
            estimatedNowCaptureNs = estimatedNowCaptureNs
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
        recordShotEndSessionState(trace, System.nanoTime())
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

        val predictedStopXCurrentModel =
            (trace.decisionCursorX + trace.decisionSpeedPxMs * trace.effectiveLatencyMs).roundToInt()
        val predictedStopErrorPx = stopCursorX - predictedStopXCurrentModel
        val impliedLatencyMs =
            if (trace.decisionSpeedPxMs > 0f) {
                (stopCursorX - trace.decisionCursorX) / trace.decisionSpeedPxMs
            } else {
                null
            }
        val postReleaseDriftPx =
            if (trace.firstPostFrameTsNs > 0L) stopCursorX - trace.firstPostCursorX else null
        val decisionToFirstPostMs =
            if (trace.firstPostFrameTsNs > 0L) {
                (trace.firstPostFrameTsNs - trace.decisionFrameTsNs) / 1_000_000f
            } else {
                null
            }
        val policyLabel = trace.phasePolicy.ifBlank { "baseline" }
        val impliedLatencyLabel =
            impliedLatencyMs?.let { String.format("%.1f", it) } ?: "n/a"
        val postReleaseDriftLabel = postReleaseDriftPx?.toString() ?: "n/a"
        val decisionToFirstPostLabel =
            decisionToFirstPostMs?.let { String.format("%.1f", it) } ?: "n/a"
        when (policyLabel) {
            "boundary_aligned" -> {
                val wakeErrorUs = abs(trace.wakeErrorNs) / 1_000f
                val intentErrorUs =
                    if (trace.targetSendIntentNs > 0L && trace.sendIntentNs > 0L) {
                        abs(trace.sendIntentNs - trace.targetSendIntentNs) / 1_000f
                    } else {
                        Float.POSITIVE_INFINITY
                    }
                val sendOverheadMs =
                    if (trace.sendFlushDoneNs >= trace.sendIntentNs && trace.sendIntentNs > 0L) {
                        (trace.sendFlushDoneNs - trace.sendIntentNs) / 1_000_000f
                    } else {
                        Float.NaN
                    }
                val rejectionReasons = mutableListOf<String>()
                if (trace.phasePolicy != "boundary_aligned") rejectionReasons += "policy_mismatch"
                if (!trace.phaseSleepApplied) rejectionReasons += "phase_sleep_not_applied"
                if (cursorGone) rejectionReasons += "cursor_gone"
                if (sendToStopCaptureMs !in 100f..180f) rejectionReasons += "send_to_stop_out_of_range"
                if (wakeErrorUs > 2_000f) rejectionReasons += "wake_error_out_of_range"
                if (intentErrorUs > 8_000f) rejectionReasons += "intent_error_out_of_range"
                if (sendOverheadMs.isNaN() || sendOverheadMs !in 0f..8f) {
                    rejectionReasons += "send_overhead_out_of_range"
                }

                if (rejectionReasons.isEmpty()) {
                    val learnedCoastMs = recordCoastSample(
                        history = boundaryAlignedCoastHistoryMs,
                        observedMs = sendToStopCaptureMs,
                        seedMs = 136f,
                        minMs = 100f,
                        maxMs = 180f,
                        windowSize = boundaryAlignedCoastWindowSize,
                        robust = true
                    )
                    Log.d(
                        TAG,
                        "LATENCY_MODEL_UPDATE_ACCEPTED: policy=$policyLabel, observedSendToStopMs=${String.format("%.1f", sendToStopCaptureMs)}, learnedCoastMs=${String.format("%.1f", learnedCoastMs)}, samples=${boundaryAlignedCoastHistoryMs.size}, method=iqr_filtered_median"
                    )
                    when {
                        sendToStopCaptureMs < 150f -> {
                            val actualBucket = "fast"
                            recordCoastSample(
                                history = boundaryAlignedFastCoastHistoryMs,
                                observedMs = sendToStopCaptureMs,
                                seedMs = 136f,
                                minMs = 100f,
                                maxMs = 180f,
                                windowSize = boundaryAlignedCoastWindowSize,
                                robust = true
                            )
                            Log.d(
                                TAG,
                                "BOUNDARY_BUCKET_UPDATE: shotId=$shotId, bucket=$actualBucket, observedSendToStopMs=${String.format("%.1f", sendToStopCaptureMs)}, learnedFastCoastMs=${formatLearnedCoastMs(getLearnedBoundaryAlignedFastCoastMsOrNull())}, learnedSlowCoastMs=${formatLearnedCoastMs(getLearnedBoundaryAlignedSlowCoastMsOrNull())}, fastSamples=${boundaryAlignedFastCoastHistoryMs.size}, slowSamples=${boundaryAlignedSlowCoastHistoryMs.size}"
                            )
                            Log.d(
                                TAG,
                                "BOUNDARY_SESSION_RESULT: shotId=$shotId, actualBucket=$actualBucket, observedSendToStopMs=${String.format("%.1f", sendToStopCaptureMs)}, sessionShotIndex=$shotId, boundaryDecisionIndex=${trace.boundaryDecisionIndex}, timeSincePreviousShotMs=${trace.boundaryTimeSincePreviousShotMsLabel}, timeSincePreviousBoundaryShotMs=${trace.boundaryTimeSincePreviousBoundaryShotMsLabel}, previousBoundaryBucket=${trace.boundaryPreviousBucket}, recentBoundaryBucketsBefore=${trace.boundaryRecentBucketsBefore}, recentBoundarySendToStopMsBefore=${trace.boundaryRecentSendToStopMsBefore}"
                            )
                            Log.d(
                                TAG,
                                "BOUNDARY_BUCKET_SHADOW_RESULT: shotId=$shotId, predictedBucket=${formatShadowBucket(trace.boundaryBucketShadowPrediction)}, actualBucket=$actualBucket, match=${trace.boundaryBucketShadowPrediction == actualBucket}"
                            )
                            recordRecentBoundaryAlignedResult(actualBucket, sendToStopCaptureMs)
                        }
                        sendToStopCaptureMs >= 155f -> {
                            val actualBucket = "slow"
                            recordCoastSample(
                                history = boundaryAlignedSlowCoastHistoryMs,
                                observedMs = sendToStopCaptureMs,
                                seedMs = 136f,
                                minMs = 100f,
                                maxMs = 180f,
                                windowSize = boundaryAlignedCoastWindowSize,
                                robust = true
                            )
                            Log.d(
                                TAG,
                                "BOUNDARY_BUCKET_UPDATE: shotId=$shotId, bucket=$actualBucket, observedSendToStopMs=${String.format("%.1f", sendToStopCaptureMs)}, learnedFastCoastMs=${formatLearnedCoastMs(getLearnedBoundaryAlignedFastCoastMsOrNull())}, learnedSlowCoastMs=${formatLearnedCoastMs(getLearnedBoundaryAlignedSlowCoastMsOrNull())}, fastSamples=${boundaryAlignedFastCoastHistoryMs.size}, slowSamples=${boundaryAlignedSlowCoastHistoryMs.size}"
                            )
                            Log.d(
                                TAG,
                                "BOUNDARY_SESSION_RESULT: shotId=$shotId, actualBucket=$actualBucket, observedSendToStopMs=${String.format("%.1f", sendToStopCaptureMs)}, sessionShotIndex=$shotId, boundaryDecisionIndex=${trace.boundaryDecisionIndex}, timeSincePreviousShotMs=${trace.boundaryTimeSincePreviousShotMsLabel}, timeSincePreviousBoundaryShotMs=${trace.boundaryTimeSincePreviousBoundaryShotMsLabel}, previousBoundaryBucket=${trace.boundaryPreviousBucket}, recentBoundaryBucketsBefore=${trace.boundaryRecentBucketsBefore}, recentBoundarySendToStopMsBefore=${trace.boundaryRecentSendToStopMsBefore}"
                            )
                            Log.d(
                                TAG,
                                "BOUNDARY_BUCKET_SHADOW_RESULT: shotId=$shotId, predictedBucket=${formatShadowBucket(trace.boundaryBucketShadowPrediction)}, actualBucket=$actualBucket, match=${trace.boundaryBucketShadowPrediction == actualBucket}"
                            )
                            recordRecentBoundaryAlignedResult(actualBucket, sendToStopCaptureMs)
                        }
                        else -> {
                            Log.d(
                                TAG,
                                "BOUNDARY_BUCKET_SKIP: shotId=$shotId, observedSendToStopMs=${String.format("%.1f", sendToStopCaptureMs)}, reason=ambiguous_middle_band"
                            )
                            Log.d(
                                TAG,
                                "BOUNDARY_SESSION_RESULT: shotId=$shotId, actualBucket=unknown, reason=ambiguous_middle_band, observedSendToStopMs=${String.format("%.1f", sendToStopCaptureMs)}"
                            )
                            Log.d(
                                TAG,
                                "BOUNDARY_BUCKET_SHADOW_RESULT: shotId=$shotId, predictedBucket=${formatShadowBucket(trace.boundaryBucketShadowPrediction)}, actualBucket=unknown, match=n/a"
                            )
                        }
                    }
                } else {
                    val sendOverheadLabel =
                        if (sendOverheadMs.isNaN()) "n/a" else String.format("%.1f", sendOverheadMs)
                    Log.d(
                        TAG,
                        "LATENCY_MODEL_UPDATE_REJECTED: policy=$policyLabel, observedSendToStopMs=${String.format("%.1f", sendToStopCaptureMs)}, wakeErrorUs=${String.format("%.0f", wakeErrorUs)}, intentErrorUs=${if (intentErrorUs.isFinite()) String.format("%.0f", intentErrorUs) else "n/a"}, sendOverheadMs=$sendOverheadLabel, reason=${rejectionReasons.joinToString("+")}"
                    )
                    Log.d(
                        TAG,
                        "BOUNDARY_SESSION_RESULT: shotId=$shotId, actualBucket=unknown, reason=rejected_sample, observedSendToStopMs=${String.format("%.1f", sendToStopCaptureMs)}"
                    )
                    Log.d(
                        TAG,
                        "BOUNDARY_BUCKET_SHADOW_RESULT: shotId=$shotId, predictedBucket=${formatShadowBucket(trace.boundaryBucketShadowPrediction)}, actualBucket=unknown, match=n/a"
                    )
                }
            }
            "wait_too_long" -> {
                if (sendToStopCaptureMs in 80f..220f) {
                    val learnedCoastMs = recordCoastSample(
                        history = waitTooLongCoastHistoryMs,
                        observedMs = sendToStopCaptureMs,
                        seedMs = 140f,
                        minMs = 90f,
                        maxMs = 170f,
                        windowSize = waitTooLongCoastWindowSize,
                        robust = false
                    )
                    Log.d(
                        TAG,
                        "LATENCY_MODEL_UPDATE: policy=$policyLabel, observedSendToStopMs=${String.format("%.1f", sendToStopCaptureMs)}, learnedCoastMs=${String.format("%.1f", learnedCoastMs)}, samples=${waitTooLongCoastHistoryMs.size}"
                    )
                }
            }
        }
        Log.d(
            TAG,
            "STOP_MODEL: shotId=$shotId, policy=$policyLabel, decisionCursorX=${trace.decisionCursorX}, " +
                "targetX=${trace.decisionTargetX}, predictedStopX=$predictedStopXCurrentModel, " +
                "finalStopX=$stopCursorX, predictedStopErrorPx=$predictedStopErrorPx, " +
                "impliedLatencyMs=$impliedLatencyLabel, postReleaseDriftPx=$postReleaseDriftLabel, " +
                "decisionToFirstPostMs=$decisionToFirstPostLabel, " +
                "sendToStopCaptureMs=${String.format("%.1f", sendToStopCaptureMs)}"
        )

        Log.d(TAG, "SHOT_END: shotId=$shotId, result=$verdict, inGreen=$inGreen, finalCursorX=$stopCursorX, decisionCursorX=${trace.decisionCursorX}, targetX=${trace.decisionTargetX}, phaseWaitMs=${String.format("%.1f", trace.plannedWaitMs)}, effectiveLatencyMs=${String.format("%.1f", trace.effectiveLatencyMs)}, targetMode=${trace.targetMode}, phaseMode=$phaseMode, phaseSleepApplied=${trace.phaseSleepApplied}, policy=${trace.phasePolicy}, decisionGreen=${trace.decisionGreenLeft}..${trace.decisionGreenRight}, stopGreen=${stopGreenLeft}..${stopGreenRight}, scoringGreen=$scoringSource")
        if (dupCount > 0) {
            Log.d(TAG, "Frame dedup: shotId=$shotId, skipped $dupCount duplicate frames")
        }
        recordShotEndSessionState(trace, System.nanoTime())
    }

    private fun getLearnedBoundaryAlignedCoastMs(): Float =
        computeLearnedCoastMs(
            history = boundaryAlignedCoastHistoryMs,
            seedMs = 136f,
            minMs = 100f,
            maxMs = 180f,
            robust = true
        )

    private fun getLearnedBoundaryAlignedFastCoastMsOrNull(): Float? =
        peekLearnedCoastMs(
            history = boundaryAlignedFastCoastHistoryMs,
            minMs = 100f,
            maxMs = 180f,
            robust = true
        )

    private fun getLearnedBoundaryAlignedSlowCoastMsOrNull(): Float? =
        peekLearnedCoastMs(
            history = boundaryAlignedSlowCoastHistoryMs,
            minMs = 100f,
            maxMs = 180f,
            robust = true
        )

    private fun getLearnedWaitTooLongCoastMs(): Float =
        computeLearnedCoastMs(
            history = waitTooLongCoastHistoryMs,
            seedMs = 140f,
            minMs = 90f,
            maxMs = 170f,
            robust = false
        )

    private fun getLiveBoundaryBlendEstimate(): BoundaryBlendEstimate {
        val learnedBoundaryCoastMs = getLearnedBoundaryAlignedCoastMs()
        val learnedFastCoastMs =
            getLearnedBoundaryAlignedFastCoastMsOrNull() ?: learnedBoundaryCoastMs
        val liveFastCoastMs = learnedFastCoastMs.coerceAtLeast(boundaryAlignedFastCoastMinMs)
        val slowSamples = boundaryAlignedSlowCoastHistoryMs.size
        val slowBlendActive = slowSamples >= boundaryAlignedMinSlowSamplesForBlend
        val learnedSlowCoastMs =
            if (slowBlendActive) {
                getLearnedBoundaryAlignedSlowCoastMsOrNull() ?: liveFastCoastMs
            } else {
                liveFastCoastMs
            }
        val rawBlendedBoundaryCoastMs =
            (liveFastCoastMs + boundaryAlignedSlowRisk * (learnedSlowCoastMs - liveFastCoastMs))
                .coerceIn(100f, 180f)
        val estimatedPeriodMs = (frameClock.estimatedPeriodNs / 1_000_000f).coerceAtLeast(1f)
        val frameRatio = rawBlendedBoundaryCoastMs / estimatedPeriodMs
        val quantizedFrames =
            when {
                frameRatio < 3.25f -> 3.0f
                frameRatio < 3.75f -> 3.5f
                else -> 4.0f
            }
        val rawQuantizedBoundaryCoastMs = quantizedFrames * estimatedPeriodMs
        val correctionMs = boundaryAlignedPostQuantizationCorrectionFrames * estimatedPeriodMs
        val correctedBoundaryCoastMs =
            (rawQuantizedBoundaryCoastMs + correctionMs).coerceIn(100f, 180f)
        return BoundaryBlendEstimate(
            learnedFastCoastMs = learnedFastCoastMs,
            liveFastCoastMs = liveFastCoastMs,
            learnedSlowCoastMs = learnedSlowCoastMs,
            rawBlendedBoundaryCoastMs = rawBlendedBoundaryCoastMs,
            estimatedPeriodMs = estimatedPeriodMs,
            frameRatio = frameRatio,
            quantizedFrames = quantizedFrames,
            rawQuantizedBoundaryCoastMs = rawQuantizedBoundaryCoastMs,
            correctionMs = correctionMs,
            correctedBoundaryCoastMs = correctedBoundaryCoastMs,
            slowSamples = slowSamples,
            slowBlendActive = slowBlendActive
        )
    }

    private fun evaluateBoundaryConfidenceGate(
        estimate: BoundaryBlendEstimate,
        pllStddevMs: Float
    ): BoundaryConfidenceGate {
        val pllStable =
            frameClock.isLocked &&
                pllStddevMs.isFinite() &&
                pllStddevMs <= boundaryAlignedMaxPllStddevMs
        val nearFrameBoundary =
            abs(estimate.frameRatio - 3.25f) < boundaryAlignedFrameBoundaryGuard ||
                abs(estimate.frameRatio - 3.75f) < boundaryAlignedFrameBoundaryGuard
        val fastFloorActive =
            estimate.liveFastCoastMs > estimate.learnedFastCoastMs + 0.01f
        val reasons = mutableListOf<String>()
        if (!pllStable) reasons += "pll_unstable"
        if (nearFrameBoundary) reasons += "near_frame_boundary"
        val baAllowed = reasons.isEmpty()
        return BoundaryConfidenceGate(
            baAllowed = baAllowed,
            reason = if (baAllowed) "ok" else reasons.joinToString(","),
            warning = if (fastFloorActive) "fast_floor_active" else "none",
            pllStddevMs = pllStddevMs,
            pllStable = pllStable,
            nearFrameBoundary = nearFrameBoundary,
            fastFloorActive = fastFloorActive,
            fallbackPolicy = if (baAllowed) "boundary_aligned" else "wait_too_long"
        )
    }

    private fun recordLocalPllPeriodStddevMs(
        transitionTimestampsNs: MutableList<Long>,
        periodEstimatesNs: MutableList<Long>,
        captureTimestampNs: Long
    ): Float {
        if (transitionTimestampsNs.isNotEmpty() && captureTimestampNs <= transitionTimestampsNs.last()) {
            return computePeriodStddevMs(periodEstimatesNs)
        }

        transitionTimestampsNs.add(captureTimestampNs)
        while (transitionTimestampsNs.size > 5) {
            transitionTimestampsNs.removeAt(0)
        }

        if (transitionTimestampsNs.size >= 2) {
            val windowSize = minOf(transitionTimestampsNs.size, 5)
            val windowStart = transitionTimestampsNs[transitionTimestampsNs.size - windowSize]
            val windowEnd = transitionTimestampsNs.last()
            val windowSteps = windowSize - 1
            val periodFromWindow = (windowEnd - windowStart) / windowSteps
            if (periodFromWindow in 15_000_000L..50_000_000L) {
                periodEstimatesNs.add(periodFromWindow)
                while (periodEstimatesNs.size > 4) {
                    periodEstimatesNs.removeAt(0)
                }
            }
        }

        return computePeriodStddevMs(periodEstimatesNs)
    }

    private fun computePeriodStddevMs(periodEstimatesNs: List<Long>): Float {
        if (periodEstimatesNs.size < 2) return Float.POSITIVE_INFINITY
        val mean = periodEstimatesNs.map { it.toDouble() }.average()
        val variance =
            periodEstimatesNs.map { periodNs ->
                val delta = periodNs.toDouble() - mean
                delta * delta
            }.average()
        return (sqrt(variance) / 1_000_000.0).toFloat()
    }

    private fun recordCoastSample(
        history: ArrayDeque<Float>,
        observedMs: Float,
        seedMs: Float,
        minMs: Float,
        maxMs: Float,
        windowSize: Int,
        robust: Boolean
    ): Float {
        history.addLast(observedMs)
        while (history.size > windowSize) {
            history.removeFirst()
        }
        return computeLearnedCoastMs(history, seedMs, minMs, maxMs, robust)
    }

    private fun computeLearnedCoastMs(
        history: Collection<Float>,
        seedMs: Float,
        minMs: Float,
        maxMs: Float,
        robust: Boolean
    ): Float {
        val estimate = if (history.isEmpty()) {
            seedMs
        } else {
            val sorted = history.sorted()
            if (robust) {
                computeIqrFilteredMedian(sorted)
            } else {
                computeMedian(sorted)
            }
        }
        return estimate.coerceIn(minMs, maxMs)
    }

    private fun predictBoundaryAlignedBucketFromRecentHistory(): String {
        if (recentBoundaryAlignedRealizedBuckets.size < boundaryAlignedShadowHistorySize) {
            return "fast"
        }
        val slowCount = recentBoundaryAlignedRealizedBuckets.count { it == "slow" }
        return if (slowCount >= 2) "slow" else "fast"
    }

    private fun recordRecentBoundaryAlignedResult(bucket: String, observedSendToStopMs: Float) {
        recentBoundaryAlignedRealizedBuckets.addLast(bucket)
        while (recentBoundaryAlignedRealizedBuckets.size > boundaryAlignedShadowHistorySize) {
            recentBoundaryAlignedRealizedBuckets.removeFirst()
        }
        recentBoundaryAlignedRealizedSendToStopMs.addLast(observedSendToStopMs)
        while (recentBoundaryAlignedRealizedSendToStopMs.size > boundaryAlignedShadowHistorySize) {
            recentBoundaryAlignedRealizedSendToStopMs.removeFirst()
        }
    }

    private fun recordShotEndSessionState(trace: ShotTrace, endNs: Long) {
        lastShotEndNs = endNs
        if (trace.phasePolicy == "boundary_aligned") {
            lastBoundaryShotEndNs = endNs
        }
    }

    private fun peekLearnedCoastMs(
        history: Collection<Float>,
        minMs: Float,
        maxMs: Float,
        robust: Boolean
    ): Float? {
        if (history.isEmpty()) return null
        val sorted = history.sorted()
        val estimate = if (robust) {
            computeIqrFilteredMedian(sorted)
        } else {
            computeMedian(sorted)
        }
        return estimate.coerceIn(minMs, maxMs)
    }

    private fun formatLearnedCoastMs(value: Float?): String =
        value?.let { String.format("%.1f", it) } ?: "n/a"

    private fun formatOptionalMs(value: Float): String =
        if (value.isFinite()) String.format("%.2f", value) else "n/a"

    private fun formatBoundaryBucketHistory(history: Collection<String>): String =
        history.joinToString(prefix = "[", postfix = "]")

    private fun formatBoundarySendToStopHistory(history: Collection<Float>): String =
        history.joinToString(prefix = "[", postfix = "]") { String.format("%.1f", it) }

    private fun formatShadowBucket(bucket: String): String =
        bucket.ifBlank { "unknown" }

    private fun formatElapsedSinceMs(previousNs: Long, currentNs: Long): String =
        if (previousNs > 0L && currentNs > previousNs) {
            String.format("%.1f", (currentNs - previousNs) / 1_000_000f)
        } else {
            "n/a"
        }

    private fun computeMedian(sortedSamples: List<Float>): Float {
        require(sortedSamples.isNotEmpty()) { "sortedSamples must not be empty" }
        val mid = sortedSamples.size / 2
        return if (sortedSamples.size % 2 == 0) {
            (sortedSamples[mid - 1] + sortedSamples[mid]) / 2f
        } else {
            sortedSamples[mid]
        }
    }

    private fun computeIqrFilteredMedian(sortedSamples: List<Float>): Float {
        if (sortedSamples.size < 5) return computeMedian(sortedSamples)

        val mid = sortedSamples.size / 2
        val lowerHalf = sortedSamples.subList(0, mid)
        val upperHalf =
            if (sortedSamples.size % 2 == 0) {
                sortedSamples.subList(mid, sortedSamples.size)
            } else {
                sortedSamples.subList(mid + 1, sortedSamples.size)
            }
        val q1 = computeMedian(lowerHalf)
        val q3 = computeMedian(upperHalf)
        val iqr = q3 - q1
        val lowerFence = q1 - 1.5f * iqr
        val upperFence = q3 + 1.5f * iqr
        val filtered =
            sortedSamples.filter { sample ->
                sample in lowerFence..upperFence
            }
        return if (filtered.isEmpty()) {
            computeMedian(sortedSamples)
        } else {
            computeMedian(filtered)
        }
    }

    fun cancel() {
        Log.d(TAG, "Shot cancelled")
        currentJob?.cancel()
        if (isRunning) {
            touchInjector.release()
            isRunning = false
        }
        // Close sleeper to interrupt any running spin-wait (safe in SHADOW: sleeper is never used)
        try { (absoluteSleeper as? java.io.Closeable)?.close() } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "HoopLandHelper"
    }
}
