package com.denusklo.hooplandhelper.core

import android.util.Log
import com.denusklo.hooplandhelper.utils.RootChecker

class TouchInjector(
    private val rootChecker: RootChecker,
    private val serviceProvider: () -> IHoopService?,
    private val adbRelay: AdbRelayClient? = null,
    private val runShellCommand: (String) -> Unit = ::defaultRunShell
) {
    private var holdX = 0
    private var holdY = 0

    fun hold(x: Int, y: Int, durationMs: Long = 3000L) {
        holdX = x
        holdY = y
        val rooted = rootChecker.isRooted()
        val relayReady = ensureRelayConnected()
        Log.d(TAG, "hold() — rooted=$rooted, relay=$relayReady, pos=($x,$y), duration=${durationMs}ms")

        if (rooted) {
            holdViaRoot(x, y, durationMs)
        } else if (relayReady) {
            Log.d(TAG, "Using ADB relay for hold")
            adbRelay?.sendHold(x, y, durationMs)
        } else {
            val service = serviceProvider()
            if (service != null) {
                Log.d(TAG, "Using AccessibilityService for hold")
                service.dispatchHoldGesture(x, y, durationMs)
            } else {
                Log.e(TAG, "No touch injection method available!")
            }
        }
    }

    fun release() {
        val rooted = rootChecker.isRooted()
        Log.d(TAG, "release() — rooted=$rooted, relay=${adbRelay?.isConnected()}, pos=($holdX,$holdY)")

        if (rooted) {
            releaseViaRoot()
        } else if (adbRelay?.isConnected() == true) {
            adbRelay.sendRelease(holdX, holdY)
        } else {
            serviceProvider()?.cancelHoldGesture()
        }
    }

    /** Try connecting relay if not already connected. Returns true if relay is usable. */
    private fun ensureRelayConnected(): Boolean {
        val relay = adbRelay ?: return false
        if (relay.isConnected()) return true
        val ok = relay.connect()
        Log.d(TAG, "ADB relay lazy connect: $ok")
        return ok
    }

    private fun holdViaRoot(x: Int, y: Int, durationMs: Long) {
        Thread {
            val cmd = "input swipe $x $y $x $y $durationMs"
            Log.d(TAG, "Root hold: $cmd")
            runShellCommand(cmd)
        }.start()
    }

    private fun releaseViaRoot() {
        val cmd = "input tap $holdX $holdY"
        Log.d(TAG, "Root release: $cmd")
        runShellCommand(cmd)
    }

    companion object {
        private const val TAG = "HoopLandHelper"
    }
}

fun defaultRunShell(cmd: String) {
    val proc = ProcessBuilder("su", "-c", cmd)
        .redirectErrorStream(true)
        .start()
    val output = proc.inputStream.bufferedReader().readText()
    val exitCode = proc.waitFor()
    if (exitCode != 0 || output.isNotBlank()) {
        Log.d("HoopLandHelper", "Shell '$cmd' → exit=$exitCode output='${output.trim()}'")
    }
}
