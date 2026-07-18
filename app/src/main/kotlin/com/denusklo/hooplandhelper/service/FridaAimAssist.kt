package com.denusklo.hooplandhelper.service

import android.content.Context
import android.util.Log
import java.io.File

/**
 * On-device aim-assist: replaces the PC-side gamehook/play.py. Ships a standalone
 * `frida-inject` binary + the pure-native `agent_play.js` il2cpp hook in assets,
 * extracts them to /data/local/tmp (via su), and keeps the agent injected into the
 * running game — re-attaching whenever the game restarts. No frida-server, no PC.
 *
 * frida-inject blocks while attached and exits when the target dies, so the
 * supervisor loop mirrors play.py's run_once/reattach loop exactly.
 *
 * Note: MIUI's "SDK keeps stopping" popup still appears — it comes from frida's
 * injector helper Java runtime, not from us, and is non-fatal.
 */
class FridaAimAssist(private val context: Context) {

    companion object {
        private const val TAG = "HoopLandHelper"
        private const val GAME_PKG = "com.koalitygame.hoopland"
        private const val FRIDA_VER = "17.15.5"          // bump when assets/frida is replaced
        private const val DIR = "/data/local/tmp"
        private const val INJECT = "$DIR/frida-inject"
        private const val AGENT = "$DIR/agent_play.js"
        private const val STAMP = "$DIR/.hoop_frida_ver"

        // aim-assist config the agent reads via globalThis.HOOK_CFG (play.py defaults)
        private const val HOOK_CFG =
            """{"aim":true,"strength":0.97,"power":false,"powerForce":0.45,"freeze":false,"teamFilter":true}"""
    }

    @Volatile private var running = false
    private var supervisor: Thread? = null
    @Volatile private var injectProc: Process? = null

    fun start() {
        if (running) return
        running = true
        supervisor = Thread({ run() }, "FridaAimAssist").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        // frida-inject runs as root via su and gets reparented, so destroying the su
        // wrapper Process does NOT kill it — kill by name explicitly (SIGTERM lets it
        // detach the agent cleanly). This is frida-inject, not frida-server: no zygote
        // hook, so a leak is harmless, but we still shut it down so "close" means stop.
        rootRead("killall frida-inject 2>/dev/null")
        injectProc?.destroy()
        injectProc = null
        supervisor?.interrupt()
        supervisor = null
    }

    private fun run() {
        try {
            extractAssets()
        } catch (e: Exception) {
            Log.e(TAG, "FRIDA_EXTRACT_FAILED: ${e.message}")
            return
        }
        // clear any frida-inject orphaned by a previous unclean exit, so we never end
        // up with two agents attached to the same game process
        rootRead("killall frida-inject 2>/dev/null")
        while (running) {
            val pid = rootRead("pidof $GAME_PKG").filter { it.isDigit() }
            if (pid.isEmpty()) {
                sleep(2000)
                continue
            }
            Log.d(TAG, "FRIDA_ATTACH: pid=$pid")
            try {
                // blocks while attached; returns when the game dies or detaches
                val p = ProcessBuilder("su", "-c", "$INJECT -p $pid -s $AGENT")
                    .redirectErrorStream(true).start()
                injectProc = p
                p.waitFor()
                injectProc = null
                Log.d(TAG, "FRIDA_DETACHED: will reattach when the game is back")
            } catch (e: Exception) {
                Log.e(TAG, "FRIDA_INJECT_ERROR: ${e.message}")
            }
            sleep(1000)
        }
    }

    /** Copy binary + config-injected agent to /data/local/tmp (via su). Idempotent
     *  via a version stamp so it only runs on first launch / after a frida upgrade. */
    private fun extractAssets() {
        if (rootRead("cat $STAMP 2>/dev/null").trim() == FRIDA_VER) return

        val cacheInject = File(context.cacheDir, "frida-inject")
        context.assets.open("frida/frida-inject").use { it.copyTo(cacheInject.outputStream()) }

        // prepend the config line the agent expects, same as play.py
        val agentBody = context.assets.open("frida/agent_play.js").bufferedReader().readText()
        val cacheAgent = File(context.cacheDir, "agent_play.js")
        cacheAgent.writeText("globalThis.HOOK_CFG = $HOOK_CFG;\n$agentBody")

        val out = rootRead(
            "cp ${cacheInject.absolutePath} $INJECT && chmod 755 $INJECT && " +
            "cp ${cacheAgent.absolutePath} $AGENT && echo $FRIDA_VER > $STAMP && echo OK"
        )
        if (!out.contains("OK")) throw IllegalStateException("cp/chmod failed: $out")
        Log.d(TAG, "FRIDA_EXTRACTED: $FRIDA_VER")
    }

    private fun rootRead(cmd: String): String = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out
    } catch (e: Exception) {
        Log.e(TAG, "ROOT_READ_ERROR: ${e.message}")
        ""
    }

    private fun sleep(ms: Long) = try { Thread.sleep(ms) } catch (e: InterruptedException) { }
}
