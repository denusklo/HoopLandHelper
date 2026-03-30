package com.denusklo.hooplandhelper.core

import com.denusklo.hooplandhelper.utils.RootChecker

class TouchInjector(
    private val rootChecker: RootChecker,
    private val serviceProvider: () -> IHoopService?,
    private val runShellCommand: (String) -> Unit = ::defaultRunShell
) {
    private var holdX = 0
    private var holdY = 0

    fun hold(x: Int, y: Int, durationMs: Long = 3000L) {
        holdX = x
        holdY = y
        if (rootChecker.isRooted()) {
            holdViaRoot(x, y, durationMs)
        } else {
            serviceProvider()?.dispatchHoldGesture(x, y, durationMs)
        }
    }

    fun release() {
        if (rootChecker.isRooted()) {
            releaseViaRoot()
        } else {
            serviceProvider()?.cancelHoldGesture()
        }
    }

    private fun holdViaRoot(x: Int, y: Int, durationMs: Long) {
        Thread {
            runShellCommand("su -c \"input swipe $x $y $x $y $durationMs\"")
        }.start()
    }

    private fun releaseViaRoot() {
        runShellCommand("su -c \"input tap $holdX $holdY\"")
    }
}

private fun defaultRunShell(cmd: String) {
    Runtime.getRuntime().exec(cmd).waitFor()
}
