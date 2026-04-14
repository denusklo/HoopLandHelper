package com.denusklo.hooplandhelper.core

import android.graphics.Color
import android.util.Log
import com.denusklo.hooplandhelper.data.HsvRange

class GreenZoneDetector(
    private val greenHsv: HsvRange,
    private val isGreenPixel: (Int) -> Boolean = { pixel -> defaultIsGreen(pixel, greenHsv) },
    private val debugDir: String? = null
) {

    private var frameDumpCount = 0

    /**
     * Result of analyzing a single bar frame.
     * @param cursorX  X position of the bright white cursor (-1 if not found)
     * @param greenLeft  Left edge of the green zone (-1 if not found)
     * @param greenRight  Right edge of the green zone (-1 if not found)
     */
    data class BarAnalysis(
        val cursorX: Int,
        val greenLeft: Int,
        val greenRight: Int
    ) {
        val greenCenter: Int get() = (greenLeft + greenRight) / 2
        val greenWidth: Int get() = greenRight - greenLeft
        val hasGreenZone: Boolean get() = greenLeft >= 0 && greenRight > greenLeft
        val hasCursor: Boolean get() = cursorX >= 0

        /**
         * Get the target X position within the green zone (default: 70% for margin).
         * This is where we want the cursor to be when we release.
         */
        fun getTargetX(greenZoneProgress: Float = 0.7f): Int {
            if (!hasGreenZone) return -1
            return greenLeft + (greenWidth * greenZoneProgress).toInt()
        }
    }

    /**
     * Analyze a bar frame to find the cursor position and green zone boundaries.
     * Scans the entire mid-row for:
     * - Brightest pixel (cursor, brightness > 600)
     * - Contiguous green-ish segment (green zone, excluding edge markers)
     */
    fun analyzeBar(width: Int, height: Int, getPixel: (Int, Int) -> Int): BarAnalysis {
        val midY = height / 2

        // Debug PNG saving disabled to reduce thermal load during gameplay
        // if (frameDumpCount < 1 && debugDir != null) {
        //     saveDebugPng(width, height, getPixel, frameDumpCount)
        //     frameDumpCount++
        // }

        // Find cursor (brightest pixel) and green zone boundaries in one pass
        var maxBrightness = 0
        var cursorX = -1

        // Track green pixels to find the green zone
        var inGreen = false
        var currentGreenStart = -1

        // Also track the widest green block (excluding edge markers at x < 20 and x > width-20)
        var bestGreenStart = -1
        var bestGreenEnd = -1
        var bestGreenWidth = 0

        for (x in 0 until width) {
            val p = getPixel(x, midY)
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val brightness = r + g + b

            // Track cursor (brightest pixel)
            if (brightness > maxBrightness) {
                maxBrightness = brightness
                cursorX = x
            }

            // Track green zone (contiguous green-ish pixels in the middle 90% of bar)
            val isGreen = isGreenPixel(p) || isGreenish(r, g, b)
            if (isGreen && x > width * 0.05 && x < width * 0.95) {
                if (!inGreen) {
                    currentGreenStart = x
                    inGreen = true
                }
            } else {
                if (inGreen) {
                    val gw = x - 1 - currentGreenStart
                    if (gw > bestGreenWidth) {
                        bestGreenStart = currentGreenStart
                        bestGreenEnd = x - 1
                        bestGreenWidth = gw
                    }
                    inGreen = false
                }
            }
        }
        // Close last green block
        if (inGreen) {
            val gw = width - 1 - currentGreenStart
            if (gw > bestGreenWidth) {
                bestGreenStart = currentGreenStart
                bestGreenEnd = width - 1
            }
        }

        // Cursor threshold
        if (maxBrightness <= 600) cursorX = -1

        // Green zone width validation: real zones are 6-14px; anything >25px is a false detection
        // (e.g., green-ish court/background being misidentified)
        if (bestGreenWidth > 25) {
            bestGreenStart = -1
            bestGreenEnd = -1
        }

        return BarAnalysis(
            cursorX = cursorX,
            greenLeft = bestGreenStart,
            greenRight = bestGreenEnd
        )
    }

    /**
     * Check if RGB values look greenish (hue 90-170, enough green dominance).
     * This is a broader check than isGreenPixel to catch the Hoop Land green zone
     * which uses colors like #387846 (RGB 56,120,70).
     */
    private fun isGreenish(r: Int, g: Int, b: Int): Boolean {
        // Green channel should be dominant
        if (g <= r || g <= b) return false
        // Minimum green value to filter out dark noise
        if (g < 40) return false
        // Calculate rough hue — green is around 90-170 degrees
        // Simple check: green significantly > red and blue
        val ratio = g.toFloat() / (r + b + 1)
        return ratio > 1.3f
    }

    /**
     * Calculate the time in ms from the first frame to when cursor reaches the target position in green zone.
     * @param first First frame analysis (cursor + green zone)
     * @param second Second frame analysis (for cursor speed measurement)
     * @param intervalMs Time between the two frames
     * @param greenZoneProgress Target position in green zone (0.0 = left edge, 0.5 = center, 0.7 = 70%, 1.0 = right edge)
     * @return Time in ms until cursor reaches target position, or -1 if invalid
     */
    fun calculateTimeToGreenMs(
        first: BarAnalysis,
        second: BarAnalysis,
        intervalMs: Long,
        greenZoneProgress: Float = 0.7f
    ): Long {
        if (!first.hasGreenZone || !first.hasCursor || !second.hasCursor) return -1

        // Cursor speed in pixels per ms
        val dx = second.cursorX - first.cursorX
        if (dx <= 0) return -1  // cursor should be moving right
        val speed = dx.toFloat() / intervalMs.toFloat()

        // Target position (e.g., 70% into the green zone for margin)
        val targetX = first.getTargetX(greenZoneProgress)

        // Distance from first cursor position to target
        val distance = targetX - first.cursorX
        if (distance <= 0) return 0  // already at or past target

        // Time to reach target
        return (distance / speed).toLong()
    }

    /**
     * Legacy method — kept for backwards compatibility with tests.
     */
    fun isGreenZoneAtCursor(width: Int, height: Int, getPixel: (Int, Int) -> Int): Boolean {
        val cursorX = findCursorX(width, height, getPixel)
        if (cursorX < 0) return false
        return isSampleGreen(height, cursorX, getPixel)
    }

    private fun findCursorX(width: Int, height: Int, getPixel: (Int, Int) -> Int): Int {
        val midY = height / 2
        var maxBrightness = 0
        var cursorX = -1
        for (x in 0 until width) {
            val p = getPixel(x, midY)
            val brightness = ((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)
            if (brightness > maxBrightness) {
                maxBrightness = brightness
                cursorX = x
            }
        }
        return if (maxBrightness > 600) cursorX else -1
    }

    private fun isSampleGreen(height: Int, cursorX: Int, getPixel: (Int, Int) -> Int): Boolean {
        val midY = height / 2
        var foundGreen = false
        for (dx in -5..5) {
            val x = (cursorX + dx).coerceAtLeast(0)
            val pixel = getPixel(x, midY)
            if (isGreenPixel(pixel)) foundGreen = true
        }
        return foundGreen
    }

    /** Save frame as debug PNG — no text dump to keep logs clean */
    private fun saveDebugPng(width: Int, height: Int, getPixel: (Int, Int) -> Int, index: Int) {
        try {
            val dir = java.io.File(debugDir!!)
            if (!dir.exists()) dir.mkdirs()
            val file = java.io.File(dir, "bar_frame_${String.format("%02d", index)}.png")
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, getPixel(x, y))
                }
            }
            file.outputStream().use { fos ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos)
            }
            Log.d(TAG, "Debug frame saved: bar_frame_${String.format("%02d", index)}.png (${width}x${height})")
        } catch (e: Exception) {
            Log.e(TAG, "saveDebugPng failed: ${e.message}")
        }
    }

    fun resetDebug() {
        frameDumpCount = 0
    }
    fun saveReleaseFrame(width: Int, height: Int, getPixel: (Int, Int) -> Int, cursorX: Int, targetX: Int) {
        if (debugDir == null) return
        try {
            val dir = java.io.File(debugDir)
            if (!dir.exists()) dir.mkdirs()
            val ts = System.currentTimeMillis()
            val file = java.io.File(dir, "release_$ts.png")
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, getPixel(x, y))
                }
            }
            file.outputStream().use { fos ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos)
            }
            Log.d(TAG, "Release frame: cursor=$cursorX, target=$targetX → saved release_$ts.png")
        } catch (e: Exception) {
            Log.e(TAG, "saveReleaseFrame failed: ${e.message}")
        }
    }
}

private const val TAG = "HoopLandHelper"

private fun defaultIsGreen(pixel: Int, greenHsv: HsvRange): Boolean {
    val hsv = FloatArray(3)
    Color.colorToHSV(pixel, hsv)
    val hueDiff = Math.abs(hsv[0] - greenHsv.hue).let { if (it > 180f) 360f - it else it }
    return hueDiff <= greenHsv.hueTolerance && hsv[1] >= greenHsv.satMin && hsv[2] >= greenHsv.valMin
}
