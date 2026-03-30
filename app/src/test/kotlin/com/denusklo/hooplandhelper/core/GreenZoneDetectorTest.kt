package com.denusklo.hooplandhelper.core

import com.denusklo.hooplandhelper.data.HsvRange
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GreenZoneDetectorTest {

    private val WHITE  = 0xFFFFFFFF.toInt()  // cursor (brightest)
    private val GREEN  = 0xFF00C800.toInt()  // perfect zone
    private val BROWN  = 0xFF8B5A2B.toInt()  // background

    private val greenHsv = HsvRange(hue = 120f, saturation = 0.8f, value = 0.8f)

    private val detector = GreenZoneDetector(
        greenHsv = greenHsv,
        isGreenPixel = { pixel -> pixel == GREEN }
    )

    private fun makeStrip(width: Int, cursorX: Int, greenStart: Int, greenEnd: Int): (Int, Int) -> Int {
        return { x, _ ->
            when {
                x == cursorX -> WHITE
                x in greenStart..greenEnd -> GREEN
                else -> BROWN
            }
        }
    }

    @Test
    fun `returns true when cursor is on the green zone`() {
        val getPixel = makeStrip(width = 20, cursorX = 10, greenStart = 8, greenEnd = 12)
        assertTrue(detector.isGreenZoneAtCursor(width = 20, height = 10, getPixel = getPixel))
    }

    @Test
    fun `returns false when cursor is not on the green zone`() {
        val getPixel = makeStrip(width = 20, cursorX = 2, greenStart = 15, greenEnd = 18)
        assertFalse(detector.isGreenZoneAtCursor(width = 20, height = 10, getPixel = getPixel))
    }

    @Test
    fun `returns false when no cursor found (no bright pixel)`() {
        val getPixel: (Int, Int) -> Int = { _, _ -> BROWN }
        assertFalse(detector.isGreenZoneAtCursor(width = 20, height = 10, getPixel = getPixel))
    }
}
