package com.denusklo.hooplandhelper.core

import com.denusklo.hooplandhelper.data.CalibrationRepository
import com.denusklo.hooplandhelper.data.HsvRange
import com.denusklo.hooplandhelper.data.ShootPosition
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
    fun `shoot holds then releases and returns true with predictive release`() = runTest {
        var held = false
        var released = false
        val injector = mock<TouchInjector>()
        doAnswer { held = true }.whenever(injector).hold(any(), any(), any())
        doAnswer { released = true }.whenever(injector).release()

        // Simulate a bar where:
        // - Green zone is at x=45..55 (dark green)
        // - Cursor starts at x=10 on frame 1, moves to x=20 on frame 2
        // - Cursor is WHITE, green zone is GREEN, rest is BLACK
        val WHITE = 0xFFFFFFFF.toInt()
        val GREEN = 0xFF00C800.toInt()
        val BLACK = 0xFF000000.toInt()

        var frameIndex = 0
        val cursorPositions = listOf(10, 20)  // cursor moves 10px per frame

        val frameProvider: FrameProvider = {
            val cursorX = if (frameIndex < cursorPositions.size) cursorPositions[frameIndex] else cursorPositions.last()
            frameIndex++
            val getPixel: (Int, Int) -> Int = { x, _ ->
                when {
                    x == cursorX -> WHITE
                    x in 45..55 -> GREEN
                    else -> BLACK
                }
            }
            BarFrame(100, 20, getPixel, timestampNs = frameIndex * 33_333_333L, isDuplicate = false)
        }

        val manager = ShotManager(
            touchInjector = injector,
            calibration = makeRepo(calibrated = true),
            frameProvider = frameProvider,
            timeoutMs = 500L,
            initialLatencyMs = 0L,  // zero latency for test
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
    fun `shoot returns false on timeout when no cursor appears`() = runTest {
        val manager = ShotManager(
            touchInjector = mock(),
            calibration = makeRepo(calibrated = true),
            frameProvider = {
                BarFrame(10, 5, { _: Int, _: Int -> 0xFF000000.toInt() },
                    timestampNs = System.nanoTime(), isDuplicate = false)
            },
            timeoutMs = 50L,
            scope = this
        )
        var result: Boolean? = null
        manager.shoot { result = it }
        advanceUntilIdle()
        assertFalse(result!!)
    }
}
