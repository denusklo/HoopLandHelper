package com.denusklo.hooplandhelper.core

import com.denusklo.hooplandhelper.utils.RootChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.mockito.kotlin.*

class TouchInjectorTest {

    @Ignore("Root touch injection starts a persistent su shell; verify this path on a rooted device")
    @Test
    fun `hold on rooted device runs root swipe shell command`() {
        val commands = mutableListOf<String>()
        val injector = TouchInjector(
            rootChecker = RootChecker(runSuId = { "uid=0(root)" }),
            serviceProvider = { null },
            runShellCommand = { cmd -> commands.add(cmd) }
        )

        injector.hold(500, 800)
        Thread.sleep(100)

        assertEquals(1, commands.size)
        assertTrue(commands[0].contains("input swipe 500 800 500 800"))
    }

    @Ignore("Root touch injection starts a persistent su shell; verify this path on a rooted device")
    @Test
    fun `release on rooted device kills hold and sends tap`() {
        val commands = mutableListOf<String>()
        val injector = TouchInjector(
            rootChecker = RootChecker(runSuId = { "uid=0(root)" }),
            serviceProvider = { null },
            runShellCommand = { cmd -> commands.add(cmd) }
        )

        injector.hold(500, 800)
        Thread.sleep(100)
        injector.release()

        assertTrue(commands.any { it.contains("input tap 500 800") })
    }

    @Test
    fun `hold on non-rooted device delegates to IHoopService`() {
        var gestureDispatched = false
        val mockService = mock<IHoopService>()
        doAnswer { gestureDispatched = true }.whenever(mockService)
            .dispatchHoldGesture(any(), any(), any())

        val injector = TouchInjector(
            rootChecker = RootChecker(runSuId = { throw RuntimeException("no su") }),
            serviceProvider = { mockService },
            runShellCommand = {}
        )

        injector.hold(500, 800)

        assertTrue(gestureDispatched)
    }

    @Test
    fun `release on non-rooted device calls cancelHoldGesture`() {
        var cancelled = false
        val mockService = mock<IHoopService>()
        doAnswer { cancelled = true }.whenever(mockService).cancelHoldGesture()

        val injector = TouchInjector(
            rootChecker = RootChecker(runSuId = { throw RuntimeException("no su") }),
            serviceProvider = { mockService },
            runShellCommand = {}
        )

        injector.hold(500, 800)
        injector.release()

        assertTrue(cancelled)
    }
}
