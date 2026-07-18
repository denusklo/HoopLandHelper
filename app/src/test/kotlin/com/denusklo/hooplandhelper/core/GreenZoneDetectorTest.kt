package com.denusklo.hooplandhelper.core

import com.denusklo.hooplandhelper.data.HsvRange
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GreenZoneDetectorTest {

    private val WHITE  = 0xFFFFFFFF.toInt()  // cursor (brightest)
    private val GREEN  = 0xFF00C800.toInt()  // perfect zone
    private val BROWN  = 0xFF8B5A2B.toInt()  // background
    private val ORANGE = 0xFFE57028.toInt()  // in-game meter orange (229,112,40)
    private val BLACK  = 0xFF000000.toInt()  // meter track

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

    // --- Orange-band fallback (no green sweet-spot on hard shots) ---

    /** Black meter track with a single colored band and a white cursor. */
    private fun makeBand(width: Int, cursorX: Int, bandStart: Int, bandEnd: Int, color: Int): (Int, Int) -> Int {
        return { x, _ ->
            when {
                x == cursorX -> WHITE
                x in bandStart until bandEnd -> color
                else -> BLACK
            }
        }
    }

    @Test
    fun `orange-only meter falls back to the orange band center`() {
        // 126px orange band, no green, cursor to the right — hard/contested shot.
        val getPixel = makeBand(width = 463, cursorX = 300, bandStart = 143, bandEnd = 269, color = ORANGE)
        val a = detector.analyzeBar(width = 463, height = 10, getPixel = getPixel)
        assertTrue(a.isOrangeFallback)
        assertTrue(a.hasGreenZone)
        assertTrue("band ${a.greenLeft}..${a.greenRight}", a.greenLeft in 140..146 && a.greenRight in 265..269)
        assertTrue("band should be wide, was ${a.greenWidth}", a.greenWidth > 40)
    }

    @Test
    fun `real green zone still wins over orange fallback`() {
        val getPixel = makeBand(width = 463, cursorX = 300, bandStart = 205, bandEnd = 235, color = GREEN)
        detector.analyzeBar(width = 463, height = 10, getPixel = getPixel)   // frame 1: pending
        val a = detector.analyzeBar(width = 463, height = 10, getPixel = getPixel)   // frame 2: accepted
        assertFalse(a.isOrangeFallback)
        assertTrue(a.hasGreenZone)
        assertTrue("green center ${a.greenCenter}", a.greenCenter in 214..226)
    }

    @Test
    fun `full-width court-floor bleed does not become a fallback zone`() {
        val getPixel: (Int, Int) -> Int = { x, _ -> if (x == 300) WHITE else BROWN }  // whole bar orange-ish
        val a = detector.analyzeBar(width = 463, height = 10, getPixel = getPixel)
        assertFalse(a.isOrangeFallback)
        assertFalse(a.hasGreenZone)
    }
}
