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
    fun `shoot holds then releases and returns true when green detected`() = runTest {
        var held = false
        var released = false
        val injector = mock<TouchInjector>()
        doAnswer { held = true }.whenever(injector).hold(any(), any(), any())
        doAnswer { released = true }.whenever(injector).release()

        val greenPixel = 0xFF00C800.toInt()
        val manager = ShotManager(
            touchInjector = injector,
            calibration = makeRepo(calibrated = true),
            frameProvider = { Triple(10, 5, { _: Int, _: Int -> greenPixel }) },
            isGreenPixelOverride = { it == greenPixel },
            scope = this
        )

        var result: Boolean? = null
        manager.shoot { result = it }
        advanceUntilIdle()

        verify(injector).hold(500, 800, 3000L)
        verify(injector).release()
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