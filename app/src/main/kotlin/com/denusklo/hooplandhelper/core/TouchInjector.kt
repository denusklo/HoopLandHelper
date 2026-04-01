package com.denusklo.hooplandhelper.core

import android.util.Log
import com.denusklo.hooplandhelper.utils.RootChecker
import java.io.OutputStream

/** Timing data from a release() call for instrumentation. */
data class ReleaseStamp(
    val sendIntentNs: Long,      // System.nanoTime() right before send
    val sendFlushDoneNs: Long,   // System.nanoTime() right after flush
    val path: String             // "sendevent" | "swipe" | "adb" | "accessibility"
)

class TouchInjector(
    private val rootChecker: RootChecker,
    private val serviceProvider: () -> IHoopService?,
    private val adbRelay: AdbRelayClient? = null,
    private val runShellCommand: (String) -> Unit = ::defaultRunShell,
    private val naturalWidth: Int = 0,
    private val naturalHeight: Int = 0,
    private val rotationProvider: () -> Int = { 0 },
) {
    private var holdX = 0
    private var holdY = 0
    private var swipeProcess: Process? = null

    // Persistent root shell for fast command execution (avoids ~300ms su fork overhead)
    private var rootShell: Process? = null
    private var rootShellWriter: OutputStream? = null

    // Cache rooted state to avoid re-checking on every release
    private var rootedCached: Boolean? = null

    // sendevent support — fast, consistent touch injection (~10ms)
    private var touchDevicePath: String? = null
    private var touchMaxX: Int = 0
    private var touchMaxY: Int = 0
    private var sendeventDetected: Boolean = false
    private var useSendevent: Boolean = false

    fun hold(x: Int, y: Int, durationMs: Long = 3000L) {
        holdX = x
        holdY = y
        val rooted = rootChecker.isRooted()
        rootedCached = rooted

        // Detect touch device once for sendevent
        if (rooted && !sendeventDetected) {
            detectTouchDevice()
            sendeventDetected = true
        }

        if (rooted) {
            Log.d(TAG, "hold() — rooted=true, pos=($x,$y), sendevent=$useSendevent")
            ensureRootShell()
            if (useSendevent) {
                holdViaSendevent(x, y)
            } else {
                holdViaRoot(x, y, durationMs)
            }
            return
        }

        val relayReady = ensureRelayConnected()
        Log.d(TAG, "hold() — rooted=false, relay=$relayReady, pos=($x,$y), duration=${durationMs}ms")

        if (relayReady) {
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

    fun release(): ReleaseStamp {
        val rooted = rootedCached ?: rootChecker.isRooted()
        Log.d(TAG, "release() — rooted=$rooted, sendevent=$useSendevent, pos=($holdX,$holdY)")

        val intentNs = System.nanoTime()
        val path: String
        val flushDoneNs: Long

        if (rooted) {
            val (p, f) = releaseViaRoot()
            path = p
            flushDoneNs = f
        } else if (adbRelay?.isConnected() == true) {
            adbRelay.sendRelease(holdX, holdY)
            path = "adb"
            flushDoneNs = System.nanoTime()
        } else {
            serviceProvider()?.cancelHoldGesture()
            path = "accessibility"
            flushDoneNs = System.nanoTime()
        }

        return ReleaseStamp(intentNs, flushDoneNs, path)
    }

    /** Try connecting relay if not already connected. Returns true if relay is usable. */
    private fun ensureRelayConnected(): Boolean {
        val relay = adbRelay ?: return false
        if (relay.isConnected()) return true
        val ok = relay.connect()
        Log.d(TAG, "ADB relay lazy connect: $ok")
        return ok
    }

    /** Open a persistent root shell for fast command execution. */
    private fun ensureRootShell() {
        if (rootShell?.isAlive == true && rootShellWriter != null) return
        try {
            val proc = Runtime.getRuntime().exec("su")
            rootShell = proc
            rootShellWriter = proc.outputStream
            // Consume output to prevent pipe buffer from blocking
            Thread { try { proc.inputStream.bufferedReader().use { it.forEachLine {} } } catch (_: Exception) {} }.start()
            Thread { try { proc.errorStream.bufferedReader().use { it.forEachLine {} } } catch (_: Exception) {} }.start()
            Log.d(TAG, "Persistent root shell opened")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open root shell: ${e.message}")
        }
    }

    /** Execute a command via the persistent root shell (<10ms vs ~300ms for su -c fork). */
    private fun rootExec(cmd: String) {
        try {
            val writer = rootShellWriter ?: return
            writer.write("$cmd\n".toByteArray())
            writer.flush()
        } catch (e: Exception) {
            Log.e(TAG, "rootExec failed: ${e.message}")
        }
    }

    /** Fallback: hold via input swipe (variable 50-150ms release overhead). */
    private fun holdViaRoot(x: Int, y: Int, durationMs: Long) {
        Thread {
            try {
                val cmd = "input swipe $x $y $x $y $durationMs"
                Log.d(TAG, "Root hold: $cmd")
                val proc = ProcessBuilder("su", "-c", cmd)
                    .redirectErrorStream(true)
                    .start()
                swipeProcess = proc
                proc.waitFor()
            } catch (_: Exception) {}
        }.start()
    }

    /** Find the touch input device and read its coordinate ranges. */
    private fun detectTouchDevice() {
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat /proc/bus/input/devices"))
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor()

            var eventPath: String? = null
            for (block in output.split("\n\n")) {
                val nameLine = block.lines().find { it.startsWith("N:") }
                if (nameLine == null) continue
                // Skip virtual/uinput devices
                if (nameLine.contains("uinput", ignoreCase = true)) continue
                if (!nameLine.contains("touch", ignoreCase = true) &&
                    !nameLine.contains("finger", ignoreCase = true) &&
                    !nameLine.contains("_ts", ignoreCase = true) &&
                    !nameLine.contains("synaptics", ignoreCase = true) &&
                    !nameLine.contains("focaltech", ignoreCase = true)) continue

                val handlerLine = block.lines().find { it.startsWith("H:") } ?: continue
                val eventMatch = Regex("event(\\d+)").find(handlerLine) ?: continue
                eventPath = "/dev/input/event${eventMatch.groupValues[1]}"
                break
            }

            if (eventPath == null) {
                Log.w(TAG, "No touch device found — falling back to input swipe")
                return
            }

            // Get abs ranges from getevent
            val absProc = Runtime.getRuntime().exec(arrayOf("su", "-c", "getevent -p $eventPath"))
            val absOutput = absProc.inputStream.bufferedReader().readText()
            absProc.waitFor()

            // 0035 = ABS_MT_POSITION_X, 0036 = ABS_MT_POSITION_Y
            val xMatch = Regex("0035[^:]*:.*?max\\s+(\\d+)").find(absOutput)
            val yMatch = Regex("0036[^:]*:.*?max\\s+(\\d+)").find(absOutput)
            if (xMatch != null) touchMaxX = xMatch.groupValues[1].toInt()
            if (yMatch != null) touchMaxY = yMatch.groupValues[1].toInt()

            touchDevicePath = eventPath
            useSendevent = true
            Log.d(TAG, "sendevent ready: $eventPath, touchMax=($touchMaxX,$touchMaxY), natural=(${naturalWidth}x${naturalHeight})")
        } catch (e: Exception) {
            Log.e(TAG, "Touch device detection failed: ${e.message}")
        }
    }

    /**
     * Map display coordinates to raw touch device coordinates.
     * Touch controller reports in portrait; display may be rotated.
     *
     * ROTATION_90 (camera on RIGHT):  tx = dy,       ty = maxY - dx
     * ROTATION_270 (camera on LEFT):  tx = maxX - dy, ty = dx
     */
    private fun mapCoords(displayX: Int, displayY: Int): Pair<Int, Int> {
        if (touchMaxX == 0 || touchMaxY == 0) return displayX to displayY

        val rotation = rotationProvider()
        return when (rotation) {
            0 -> {
                // Portrait: direct mapping
                displayX.coerceIn(0, touchMaxX) to displayY.coerceIn(0, touchMaxY)
            }
            1 -> {
                // ROTATION_90: raw_x = maxX - displayY, raw_y = displayX
                (touchMaxX - displayY).coerceIn(0, touchMaxX) to displayX.coerceIn(0, touchMaxY)
            }
            3 -> {
                // ROTATION_270: raw_x = displayY, raw_y = maxY - displayX
                displayY.coerceIn(0, touchMaxX) to (touchMaxY - displayX).coerceIn(0, touchMaxY)
            }
            else -> displayX to displayY
        }
    }

    /** Hold via sendevent: touch DOWN on the raw device (~15ms, consistent). */
    private fun holdViaSendevent(x: Int, y: Int) {
        val device = touchDevicePath ?: return
        val (tx, ty) = mapCoords(x, y)
        val rotation = rotationProvider()

        Log.d(TAG, "sendevent DOWN: display($x,$y) -> touch($tx,$ty), rotation=$rotation")

        rootExec("sendevent $device 3 57 1")   // ABS_MT_TRACKING_ID = 1
        rootExec("sendevent $device 3 53 $tx") // ABS_MT_POSITION_X
        rootExec("sendevent $device 3 54 $ty") // ABS_MT_POSITION_Y
        rootExec("sendevent $device 1 330 1")  // BTN_TOUCH = DOWN
        rootExec("sendevent $device 0 0 0")    // SYN_REPORT
    }

    /** Returns (path, flushDoneNs) for release via root shell. */
    private fun releaseViaRoot(): Pair<String, Long> {
        if (useSendevent) {
            val device = touchDevicePath!!
            val cmd = "sendevent $device 3 57 -1; sendevent $device 1 330 0; sendevent $device 0 0 0"
            val flushNs = rootExecTimed(cmd)
            Log.d(TAG, "sendevent release")
            return "sendevent" to flushNs
        } else {
            swipeProcess?.destroy()
            swipeProcess = null
            val cmd = "input swipe $holdX $holdY $holdX $holdY 1"
            Log.d(TAG, "Root release (fallback): $cmd")
            rootExec(cmd)
            return "swipe" to System.nanoTime()
        }
    }

    /** Execute a command via the persistent root shell. Returns flush-complete timestamp. */
    private fun rootExecTimed(cmd: String): Long {
        try {
            val writer = rootShellWriter ?: return System.nanoTime()
            writer.write("$cmd\n".toByteArray())
            writer.flush()
            return System.nanoTime()
        } catch (e: Exception) {
            Log.e(TAG, "rootExec failed: ${e.message}")
            return System.nanoTime()
        }
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
