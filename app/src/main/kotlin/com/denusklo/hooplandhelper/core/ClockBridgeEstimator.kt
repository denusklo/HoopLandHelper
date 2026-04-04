package com.denusklo.hooplandhelper.core

import kotlin.math.abs

data class ClockBridgeSnapshot(
    val offsetNs: Long,
    val jitterNs: Long,
    val sampleCount: Int
)

/**
 * Estimates the offset between capture-domain timestamps (Image.timestamp, VSYNC-aligned
 * CLOCK_MONOTONIC) and system nanoTime observed when the frame is cached.
 *
 * The offset is captureLag = observedNs - captureNs, estimated via rolling median.
 * Jitter is the median absolute deviation of the offset samples.
 *
 * Used to convert PLL-predicted capture-domain boundaries into system nanoTime targets
 * for the absolute-time sleeper.
 */
class ClockBridgeEstimator(
    private val maxSamples: Int = 12
) {
    private val offsets = mutableListOf<Long>()

    fun record(captureNs: Long, observedNs: Long) {
        offsets.add(observedNs - captureNs)
        if (offsets.size > maxSamples) offsets.removeAt(0)
    }

    fun snapshot(): ClockBridgeSnapshot? {
        if (offsets.size < 3) return null
        val sorted = offsets.sorted()
        val median = sorted[sorted.size / 2]
        val deviations = sorted.map { abs(it - median) }.sorted()
        val mad = deviations[deviations.size / 2]
        return ClockBridgeSnapshot(median, mad, offsets.size)
    }

    fun toSystemTime(captureNs: Long): Long? {
        val snap = snapshot() ?: return null
        return captureNs + snap.offsetNs
    }

    fun reset() {
        offsets.clear()
    }
}
