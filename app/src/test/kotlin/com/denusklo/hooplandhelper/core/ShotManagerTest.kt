package com.denusklo.hooplandhelper.core

import com.denusklo.hooplandhelper.data.CalibrationRepository
import com.denusklo.hooplandhelper.data.HsvRange
import com.denusklo.hooplandhelper.data.ShootPosition
import android.graphics.Color
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class ShotManagerTest {

    private val shootPos = ShootPosition(500, 800)
    private val greenHsv = HsvRange(120f, 0.8f, 0.8f)

    private fun makeRepo(calibrated: Boolean): CalibrationRepository {
        val repo = mock<CalibrationRepository>()
        whenever(repo.isCalibrated()).thenReturn(calibrated)
        whenever(repo.loadShootPosition()).thenReturn(if (calibrated) shootPos else null)
        whenever(repo.loadGreenHsv()).thenReturn(if (calibrated) greenHsv else null)
        return repo
    }

    @Test
    fun `shoot calls onResult(false) immediately when not calibrated`() = runTest {
        val manager = ShotManager(
            touchInjector = mock(),
            calibration = makeRepo(calibrated = false),
            frameProvider = { null },
            scope = this
        )
        var result: Boolean? = null
        manager.shoot { result = it }
        advanceUntilIdle()
        assertFalse(result!!)
    }

    @Test
    fun `shoot holds then releases and returns true when green detected`() = runTest {
        var held = false
        var released = false
        val injector = mock<TouchInjector>()
        doAnswer { held = true }.whenever(injector).hold(any(), any(), any())
        doAnswer { released = true }.whenever(injector).release()

        // Cursor (WHITE) at x=50, green zone from x=45 to x=55
        val WHITE = 0xFFFFFFFF.toInt()  // brightness 765
        val GREEN = 0xFF00C800.toInt()  // green zone color
        val BROWN = 0xFF8B5A2B.toInt()  // background

        val cursorX = 50
        val greenStart = 45
        val greenEnd = 55

        val getPixel: (Int, Int) -> Int = { x, _ ->
            when {
                x == cursorX -> WHITE
                x in greenStart..greenEnd -> GREEN
                else -> BROWN
            }
        }

        val manager = ShotManager(
            touchInjector = injector,
            calibration = makeRepo(calibrated = true),
            frameProvider = { Triple(100, 20, getPixel) },
            isGreenPixelOverride = { pixel -> pixel == GREEN },
            scope = this
        )

        var result: Boolean? = null
        manager.shoot { result = it }
        advanceUntilIdle()

        assertTrue(held)
        assertTrue(released)
        assertTrue(result!!)
    }

    @Test
    fun `shoot returns false on timeout when no green detected`() = runTest {
        val manager = ShotManager(
            touchInjector = mock(),
            calibration = makeRepo(calibrated = true),
            frameProvider = { Triple(10, 5, { _: Int, _: Int -> 0xFF000000.toInt() }) },
            timeoutMs = 50L,
            scope = this
        )
        var result: Boolean? = null
        manager.shoot { result = it }
        advanceUntilIdle()
        assertFalse(result!!)
    }
}