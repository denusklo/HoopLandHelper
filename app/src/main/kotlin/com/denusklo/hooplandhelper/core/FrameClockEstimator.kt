package com.denusklo.hooplandhelper.core

import android.util.Log
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Software PLL that estimates the game's frame period and predicts future frame boundaries
 * from cursor-position-change timestamps.
 *
 * IMPORTANT: All timestamps fed to this PLL must be in the CAPTURE domain
 * (frame.timestampNs from Image.timestamp, VSYNC-aligned). This keeps the PLL
 * tracking the game's actual frame cadence, not our polling loop jitter.
 *
 * The game runs at ~30fps (33.33ms period). Cursor moves in discrete steps at each game frame.
 * By recording the capture timestamp of each cursor-position-change, we can:
 * - Estimate the game's frame period (elapsed time / step count over multiple transitions)
 * - Predict when the next game frame boundary will occur
 * - Time our release to land just after a boundary for consistent latency
 *
 * Build 4b: No per-interval normalization. Period is estimated from total elapsed
 * capture time divided by number of transitions — naturally smooths over missed steps.
 * Shadow mode only — PLL does not control release timing.
 */
class FrameClockEstimator {

    // Observed frame transition timestamps (nanoseconds, capture-domain)
    private val transitions = mutableListOf<Long>()
    private val MAX_TRANSITIONS = 30

    // PLL state
    var estimatedPeriodNs: Long = 33_333_333L  // default: 30fps
        private set
    var isLocked: Boolean = false
        private set

    // Last observed transition — the anchor for predictions
    var lastTransitionNs: Long = 0L
        private set

    // Period estimates from sliding window of transitions
    private val recentPeriods = mutableListOf<Long>()
    private val LOCK_WINDOW = 4

    /**
     * Called when cursor position changes (a game-frame boundary is detected).
     * @param captureTimestampNs  Image.timestamp (CLOCK_MONOTONIC, VSYNC-aligned) when the
     *                            cursor movement was captured. NOT System.nanoTime().
     */
    fun recordFrameTransition(captureTimestampNs: Long) {
        if (transitions.isNotEmpty() && captureTimestampNs <= transitions.last()) return

        transitions.add(captureTimestampNs)
        if (transitions.size > MAX_TRANSITIONS) transitions.removeAt(0)

        if (transitions.size >= 2) {
            // Estimate period from total elapsed time / step count over the window.
            // This naturally handles missed cursor steps: if we see 3 transitions spanning
            // 2 game frames each, the average gives ~66ms/2 = 33ms per frame.
            val windowStart = transitions[transitions.size - Math.min(transitions.size, LOCK_WINDOW + 1)]
            val windowEnd = transitions.last()
            val windowSpan = windowEnd - windowStart
            val windowSteps = Math.min(transitions.size - 1, LOCK_WINDOW)
            val periodFromWindow = windowSpan / windowSteps

            if (periodFromWindow in 15_000_000L..50_000_000L) {
                recentPeriods.add(periodFromWindow)
                if (recentPeriods.size > LOCK_WINDOW) recentPeriods.removeAt(0)
                updateEstimate()
            }
        }

        // Anchor to most recent transition — self-corrects for clock drift
        lastTransitionNs = captureTimestampNs
    }

    private fun updateEstimate() {
        if (recentPeriods.size < 3) return

        // Median period (robust to outliers)
        val sorted = recentPeriods.sorted()
        estimatedPeriodNs = sorted[sorted.size / 2]

        // Lock detection: once locked, stay locked for the rest of the shot.
        if (recentPeriods.size >= LOCK_WINDOW && !isLocked) {
            val mean = recentPeriods.average()
            val variance = recentPeriods.map { (it - mean) * (it - mean) }.average()
            val stddev = sqrt(variance)
            if (stddev < 3_000_000L) {  // < 3ms jitter => locked
                isLocked = true
                Log.d(TAG, "PLL LOCKED: period=${String.format("%.2f", estimatedPeriodNs / 1_000_000f)}ms, stddev=${String.format("%.2f", stddev / 1_000_000f)}ms")
            }
        }
    }

    /**
     * Predict the first frame boundary at or after the given timestamp.
     * Anchored to lastTransitionNs — drift self-corrects on each new cursor movement.
     */
    fun nextBoundaryAfter(timeNs: Long): Long {
        if (lastTransitionNs == 0L) return timeNs  // no data
        val periodsAfterAnchor = ceil((timeNs - lastTransitionNs).toDouble() / estimatedPeriodNs.toDouble()).toLong()
        return lastTransitionNs + periodsAfterAnchor * estimatedPeriodNs
    }

    fun reset() {
        transitions.clear()
        recentPeriods.clear()
        isLocked = false
        lastTransitionNs = 0L
        estimatedPeriodNs = 33_333_333L
    }

    companion object {
        private const val TAG = "HoopLandHelper"
    }
}
