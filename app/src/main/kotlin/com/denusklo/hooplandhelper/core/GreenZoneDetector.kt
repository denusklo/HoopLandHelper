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
    private val MAX_DEBUG_FRAMES = 10

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
    }

    /**
     * Analyze a bar frame to find the cursor position and green zone boundaries.
     * Scans the entire mid-row for:
     * - Brightest pixel (cursor, brightness > 600)
     * - Contiguous green-ish segment (green zone, excluding edge markers)
     */
    fun analyzeBar(width: Int, height: Int, getPixel: (Int, Int) -> Int): BarAnalysis {
        val midY = height / 2

        // Dump first few frames for debugging
        if (frameDumpCount < 3) {
            dumpFrame(width, height, getPixel, frameDumpCount)
            frameDumpCount++
        }

        // Find cursor (brightest pixel) and green zone boundaries in one pass
        var maxBrightness = 0
        var cursorX = -1

        // Track green pixels to find the green zone
        var greenStart = -1
        var greenEnd = -1
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
                bestGreenWidth = gw
            }
        }

        // Cursor threshold
        if (maxBrightness <= 600) cursorX = -1

        val analysis = BarAnalysis(
            cursorX = cursorX,
            greenLeft = bestGreenStart,
            greenRight = bestGreenEnd
        )

        if (frameDumpCount <= 5) {
            Log.d(TAG, "analyzeBar: cursorX=${analysis.cursorX} (maxBr=$maxBrightness), greenZone=${analysis.greenLeft}..${analysis.greenRight} (width=${analysis.greenWidth})")
        }

        return analysis
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
     * Calculate the time in ms from the first frame to when cursor reaches green zone center.
     * @param first First frame analysis (cursor + green zone)
     * @param second Second frame analysis (for cursor speed measurement)
     * @param intervalMs Time between the two frames
     * @return Time in ms until cursor reaches green zone center, or -1 if invalid
     */
    fun calculateTimeToGreenMs(
        first: BarAnalysis,
        second: BarAnalysis,
        intervalMs: Long
    ): Long {
        if (!first.hasGreenZone || !first.hasCursor || !second.hasCursor) return -1

        // Cursor speed in pixels per ms
        val dx = second.cursorX - first.cursorX
        if (dx <= 0) return -1  // cursor should be moving right
        val speed = dx.toFloat() / intervalMs.toFloat()

        // Distance from first cursor position to green zone center
        val distance = first.greenCenter - first.cursorX
        if (distance <= 0) return 0  // already at or past green zone

        // Time to reach green center
        return (distance / speed).toLong()
    }

    /**
     * Calculate the delay in ms before releasing, based on two analyses.
     * @param first First frame analysis (cursor + green zone)
     * @param second Second frame analysis (for cursor speed measurement)
     * @param intervalMs Time between the two frames
     * @param relayLatencyMs Expected ADB relay latency to compensate for
     * @return Delay in ms before release, or 0 if should release immediately, or -1 if invalid
     */
    fun calculateReleaseDelayMs(
        first: BarAnalysis,
        second: BarAnalysis,
        intervalMs: Long,
        relayLatencyMs: Long = 120L
    ): Long {
        if (!first.hasGreenZone || !first.hasCursor || !second.hasCursor) return -1

        // Cursor speed in pixels per ms
        val dx = second.cursorX - first.cursorX
        if (dx <= 0) return -1  // cursor should be moving right
        val speed = dx.toFloat() / intervalMs.toFloat()

        // Distance from first cursor position to green zone center
        val distance = first.greenCenter - first.cursorX
        if (distance <= 0) return 0  // already at or past green zone

        // Time to reach green center
        val timeToGreen = (distance / speed).toLong()

        // Subtract relay latency to release early
        val releaseDelay = timeToGreen - relayLatencyMs

        Log.d(TAG, "calculateReleaseDelay: speed=${String.format("%.3f", speed)} px/ms, distance=$distance px, timeToGreen=${timeToGreen}ms, relayLatency=${relayLatencyMs}ms, releaseDelay=${releaseDelay}ms")

        return releaseDelay.coerceAtLeast(0)
    }

    /**
     * Legacy method — kept for backwards compatibility with tests.
     */
    fun isGreenZoneAtCursor(width: Int, height: Int, getPixel: (Int, Int) -> Int): Boolean {
        if (frameDumpCount < 3) {
            dumpFrame(width, height, getPixel, frameDumpCount)
            frameDumpCount++
        }
        val cursorX = findCursorX(width, height, getPixel)
        if (cursorX < 0) {
            Log.d(TAG, "isGreenZoneAtCursor: no cursor found (width=$width, height=$height)")
            return false
        }
        val result = isSampleGreen(height, cursorX, getPixel)
        if (frameDumpCount <= 5) {
            Log.d(TAG, "isGreenZoneAtCursor: cursorX=$cursorX, result=$result")
        }
        return result
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
        if (frameDumpCount < MAX_DEBUG_FRAMES) {
            Log.d(TAG, "findCursorX: width=$width, maxBrightness=$maxBrightness at x=$cursorX (threshold=600)")
        }
        return if (maxBrightness > 600) cursorX else -1
    }

    private fun isSampleGreen(height: Int, cursorX: Int, getPixel: (Int, Int) -> Int): Boolean {
        val midY = height / 2
        var foundGreen = false
        for (dx in -5..5) {
            val x = (cursorX + dx).coerceAtLeast(0)
            val pixel = getPixel(x, midY)
            val green = isGreenPixel(pixel)
            if (green) foundGreen = true
            if (frameDumpCount < MAX_DEBUG_FRAMES && dx % 2 == 0) {
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val hsv = FloatArray(3)
                android.graphics.Color.colorToHSV(pixel, hsv)
                Log.d(TAG, "  cursorX+${dx}: ARGB=($r,$g,$b) HSV=(${String.format("%.0f",hsv[0])},${String.format("%.2f",hsv[1])},${String.format("%.2f",hsv[2])}) green=$green")
            }
        }
        return foundGreen
    }

    /** Dump frame for debugging — saves PNG and logs pixel values */
    private fun dumpFrame(width: Int, height: Int, getPixel: (Int, Int) -> Int, index: Int) {
        try {
            if (debugDir != null) {
                val dir = java.io.File(debugDir)
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
                Log.d(TAG, "Frame #$index saved to ${file.absolutePath} (${width}x${height})")
            }

            val midY = height / 2
            val sb = StringBuilder()
            var maxBr = 0
            var maxBrX = -1
            for (x in 0 until width) {
                val p = getPixel(x, midY)
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val brightness = r + g + b
                if (brightness > maxBr) { maxBr = brightness; maxBrX = x }
                if (x % (width / 20).coerceAtLeast(1) == 0) {
                    sb.append("[$x:#%02X%02X%02X=$brightness]".format(r, g, b))
                }
            }
            Log.d(TAG, "Frame #$index (${width}x${height}) mid-row: $sb")
            Log.d(TAG, "Frame #$index max brightness=$maxBr at x=$maxBrX (threshold=600)")

            if (index == 0) {
                val colorSb = StringBuilder()
                colorSb.append("\n  Full mid-row colors:")
                for (x in 0 until width step (width / 30).coerceAtLeast(1)) {
                    val p = getPixel(x, midY)
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    val brightness = r + g + b
                    val hsv = FloatArray(3)
                    android.graphics.Color.colorToHSV(p, hsv)
                    colorSb.append("\n    x=$x: RGB=($r,$g,$b) brightness=$brightness HSV=(${String.format("%.0f",hsv[0])},${String.format("%.2f",hsv[1])},${String.format("%.2f",hsv[2])})")
                }
                Log.d(TAG, "Frame #$index full mid-row:$colorSb")
            }
        } catch (e: Exception) {
            Log.e(TAG, "dumpFrame failed: ${e.message}")
        }
    }

    fun resetDebug() {
        frameDumpCount = 0
    }
}

private const val TAG = "HoopLandHelper"

private fun defaultIsGreen(pixel: Int, greenHsv: HsvRange): Boolean {
    val hsv = FloatArray(3)
    Color.colorToHSV(pixel, hsv)
    val hueDiff = Math.abs(hsv[0] - greenHsv.hue).let { if (it > 180f) 360f - it else it }
    return hueDiff <= greenHsv.hueTolerance && hsv[1] >= greenHsv.satMin && hsv[2] >= greenHsv.valMin
}
