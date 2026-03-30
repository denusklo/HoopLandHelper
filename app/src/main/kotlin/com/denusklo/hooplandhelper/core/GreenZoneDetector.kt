package com.denusklo.hooplandhelper.core

import android.graphics.Color
import android.util.Log
import com.denusklo.hooplandhelper.data.HsvRange

class GreenZoneDetector(
    private val greenHsv: HsvRange,
    private val isGreenPixel: (Int) -> Boolean = { pixel -> defaultIsGreen(pixel, greenHsv) }
) {

    fun isGreenZoneAtCursor(width: Int, height: Int, getPixel: (x: Int, y: Int) -> Int): Boolean {
        val cursorX = findCursorX(width, height, getPixel)
        if (cursorX < 0) {
            Log.d(TAG, "isGreenZoneAtCursor: no cursor found (width=$width, height=$height)")
            return false
        }
        val result = isSampleGreen(height, cursorX, getPixel)
        Log.d(TAG, "isGreenZoneAtCursor: cursorX=$cursorX, result=$result")
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
        return if (maxBrightness > 600) cursorX else -1
    }

    private fun isSampleGreen(height: Int, cursorX: Int, getPixel: (Int, Int) -> Int): Boolean {
        val midY = height / 2
        for (dx in -5..5) {
            val pixel = getPixel((cursorX + dx).coerceAtLeast(0), midY)
            if (isGreenPixel(pixel)) return true
        }
        return false
    }
}

private const val TAG = "HoopLandHelper"

private fun defaultIsGreen(pixel: Int, greenHsv: HsvRange): Boolean {
    val hsv = FloatArray(3)
    Color.colorToHSV(pixel, hsv)
    val hueDiff = Math.abs(hsv[0] - greenHsv.hue).let { if (it > 180f) 360f - it else it }
    return hueDiff <= greenHsv.hueTolerance && hsv[1] >= greenHsv.satMin && hsv[2] >= greenHsv.valMin
}
