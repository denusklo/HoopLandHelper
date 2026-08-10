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
        private const val TEAM_FILE = "$DIR/.hoop_team"   // agent writes detected side (home/road/empty); we poll it
        private const val AIMMODE_FILE = "$DIR/.hoop_aimmode"   // WE write 'team'/'player'; the agent re-reads it live

        // aim-assist config the agent reads via globalThis.HOOK_CFG (play.py defaults)
        private const val HOOK_CFG =
            """{"aim":true,"strength":0.97,"power":false,"powerForce":0.45,"freeze":false,"teamFilter":true,"aimTeam":false}"""
    }

    @Volatile private var running = false
    // Manual attach gate (mirrors autoplay.py): the supervisor stays idle until the user arms it,
    // so we never attach during the game's LOAD (which trips PAIRIP and sticks the game). Auto-cleared
    // after each detach/crash so a reload never silently re-attaches.
    @Volatile var armed = false
        private set
    private var supervisor: Thread? = null

    /** Side the agent auto-detected: "home", "road", or null (detecting / cold sim). */
    @Volatile var detectedSide: String? = null
        private set
    /** Notified whenever detectedSide changes. */
    var onTeam: ((String?) -> Unit)? = null
    /** Attach lifecycle: "off" | "attaching" | "attached" | "detached". */
    var onState: ((String) -> Unit)? = null

    // Live aim mode: true = whole team 100%, false = controlled avatar only. Persisted; the agent
    // re-reads the file at runtime, so toggling takes effect mid-match with NO re-inject.
    private val prefs = context.getSharedPreferences("frida_aim", Context.MODE_PRIVATE)
    @Volatile var aimTeam: Boolean = prefs.getBoolean("aimTeam", false)
        private set
    /** Notified when the aim mode changes. */
    var onAimMode: ((Boolean) -> Unit)? = null

    /** Write the aim mode to the file the agent reads (chmod 666 so the game-uid agent can read it). */
    private fun publishAimMode() {
        rootRead("printf %s ${if (aimTeam) "team" else "player"} > $AIMMODE_FILE && chmod 666 $AIMMODE_FILE")
    }

    /** Toggle/set the aim mode: persist, publish to the file (agent picks it up within ~0.5s). */
    fun setAimMode(team: Boolean) {
        aimTeam = team
        prefs.edit().putBoolean("aimTeam", team).apply()
        publishAimMode()
        onAimMode?.invoke(team)
    }

    fun start() {
        if (running) return
        running = true
        supervisor = Thread({ run() }, "FridaAimAssist").apply { isDaemon = true; start() }
    }

    /** Arm the attach gate: the supervisor attaches on the next loop iteration once the game pid exists. */
    fun arm() { armed = true }

    fun stop() {
        running = false
        armed = false
        rootRead("rm -f $TEAM_FILE 2>/dev/null")
        if (detectedSide != null) { detectedSide = null; onTeam?.invoke(null) }
        onState?.invoke("off")
        // With eternalize (-e) the frida-inject process has already exited after loading; the agent
        // lives INSIDE the game process now, so there is nothing of ours to kill (killall is a harmless
        // no-op that also mops up any non-eternalized leftover). The hooks persist until the game dies.
        rootRead("killall frida-inject 2>/dev/null")
        supervisor?.interrupt()
        supervisor = null
    }

    private fun run() {
        // Kill any orphaned frida-inject FIRST: a running copy holds the binary open, so extractAssets'
        // `cp` over /data/local/tmp/frida-inject would fail with "Text file busy" (ETXTBSY) and the whole
        // deploy would abort. Clearing it up front also avoids two agents on one game process.
        rootRead("killall -9 frida-inject 2>/dev/null")
        try {
            extractAssets()
        } catch (e: Exception) {
            Log.e(TAG, "FRIDA_EXTRACT_FAILED: ${e.message}")
            return
        }
        while (running) {
            // Idle until the user arms the gate — no pidof spam, no load-time attach.
            if (!armed) { sleep(500); continue }
            val pid = rootRead("pidof $GAME_PKG").filter { it.isDigit() }
            if (pid.isEmpty()) {
                sleep(1000)   // armed but the game isn't up yet
                continue
            }
            Log.d(TAG, "FRIDA_ATTACH: pid=$pid")
            onState?.invoke("attaching")
            // Pre-create the side file writable by the game process: the eternalized agent runs as the
            // game's uid (NOT root), so it cannot create a fresh root-owned file — chmod 666 lets it write.
            rootRead("rm -f $TEAM_FILE; : > $TEAM_FILE && chmod 666 $TEAM_FILE")
            publishAimMode()   // seed the live aim-mode file with the persisted choice before the agent loads
            if (detectedSide != null) { detectedSide = null; onTeam?.invoke(null) }
            try {
                // -R qjs (QuickJS runtime) -e (eternalize): frida-inject loads the script, detaches the
                // ptrace so PAIRIP sees no persistent tracer, and EXITS — the hooks stay resident in the
                // game. So this returns quickly on success; there is no long-lived stdout to stream.
                val out = ProcessBuilder("su", "-c", "$INJECT -R qjs -e -p $pid -s $AGENT")
                    .redirectErrorStream(true).start()
                    .inputStream.bufferedReader().readText()
                out.lineSequence().forEach { if (it.isNotBlank()) Log.d(TAG, "FRIDA_OUT: $it") }
                Log.d(TAG, "FRIDA_ETERNALIZED: agent resident in pid=$pid")
                onState?.invoke("attached")
            } catch (e: Exception) {
                Log.e(TAG, "FRIDA_INJECT_ERROR: ${e.message}")
                armed = false
                onState?.invoke("detached")
                sleep(1000)
                continue
            }
            // Fire-and-forget done. Now poll: watch the game pid (detach on death) and read the side file
            // the resident agent writes. ONE su per tick (pid + side together) every 2s to minimize the
            // Magisk "granted superuser" toasts and system load.
            while (running && armed) {
                val out = rootRead("echo P:${'$'}(pidof $GAME_PKG); echo T:${'$'}(cat $TEAM_FILE 2>/dev/null)")
                val pid2 = out.lineSequence().firstOrNull { it.startsWith("P:") }?.removePrefix("P:")?.trim() ?: ""
                if (pid2.none { it.isDigit() }) break   // game died -> detached
                val team = out.lineSequence().firstOrNull { it.startsWith("T:") }?.removePrefix("T:")?.trim() ?: ""
                val side = if (team == "home" || team == "road") team else null
                if (side != detectedSide) { detectedSide = side; onTeam?.invoke(side) }
                sleep(2000)
            }
            Log.d(TAG, "FRIDA_DETACHED")
            // Require an explicit re-arm after any detach/crash, so a game reload never auto-reattaches.
            armed = false
            if (detectedSide != null) { detectedSide = null; onTeam?.invoke(null) }
            onState?.invoke("detached")
            sleep(1000)
        }
    }

    /** Copy binary + config-injected agent to /data/local/tmp (via su). Idempotent
     *  via a version stamp so it only runs on first launch / after a frida upgrade. */
    private fun extractAssets() {
        // The agent text the app will deploy (config line + hook body), same as play.py.
        val agentBody = context.assets.open("frida/agent_play.js").bufferedReader().readText()
        val agentText = "globalThis.HOOK_CFG = $HOOK_CFG;\n$agentBody"
        // Stamp reflects the frida version AND the agent+config CONTENT, so any hook/config change
        // re-extracts — even though /data/local/tmp survives app reinstalls (a fixed FRIDA_VER stamp
        // would leave the old agent in place after an update).
        val stamp = "$FRIDA_VER-" + Integer.toHexString(agentText.hashCode())
        if (rootRead("cat $STAMP 2>/dev/null").trim() == stamp) return

        val cacheInject = File(context.cacheDir, "frida-inject")
        context.assets.open("frida/frida-inject").use { it.copyTo(cacheInject.outputStream()) }
        val cacheAgent = File(context.cacheDir, "agent_play.js")
        cacheAgent.writeText(agentText)

        val out = rootRead(
            "cp ${cacheInject.absolutePath} $INJECT && chmod 755 $INJECT && " +
            "cp ${cacheAgent.absolutePath} $AGENT && echo '$stamp' > $STAMP && echo OK"
        )
        if (!out.contains("OK")) throw IllegalStateException("cp/chmod failed: $out")
        Log.d(TAG, "FRIDA_EXTRACTED: $stamp")
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
