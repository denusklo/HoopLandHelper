package com.denusklo.hooplandhelper.core

/**
 * A captured frame from the screen, with timing metadata.
 * @param width Width of the captured region in pixels
 * @param height Height of the captured region in pixels
 * @param getPixel Lambda to read a pixel at (x, y) as ARGB int
 * @param timestampNs CLOCK_MONOTONIC nanoseconds from Image.timestamp (VSYNC-aligned, capture-domain)
 * @param isDuplicate True if this frame has the same timestamp as the previous one (game hasn't updated)
 * @param frameSeq Sequential frame counter (increments per unique captured frame)
 * @param frameObservedNs System.nanoTime() when the app cached this frame (observation-domain)
 */
data class BarFrame(
    val width: Int,
    val height: Int,
    val getPixel: (Int, Int) -> Int,
    val timestampNs: Long,
    val isDuplicate: Boolean,
    val frameSeq: Long = 0,
    val frameObservedNs: Long = 0L
)
