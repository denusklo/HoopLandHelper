package com.denusklo.hooplandhelper.core

import android.util.Log
import java.io.OutputStreamWriter
import java.net.Socket

class AdbRelayClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 9999
) {
    private var socket: Socket? = null
    private var writer: OutputStreamWriter? = null

    fun connect(): Boolean {
        return try {
            socket = Socket(host, port)
            socket?.soTimeout = 3000
            writer = OutputStreamWriter(socket!!.getOutputStream())
            Log.d(TAG, "ADB relay connected to $host:$port")
            true
        } catch (e: Exception) {
            Log.w(TAG, "ADB relay connect failed: ${e.message}")
            false
        }
    }

    fun sendHold(x: Int, y: Int, durationMs: Long) {
        send("hold $x $y $durationMs")
    }

    fun sendRelease(x: Int, y: Int) {
        send("release $x $y")
    }

    private fun send(msg: String) {
        try {
            writer?.write("$msg\n")
            writer?.flush()
            Log.d(TAG, "ADB relay sent: $msg")
        } catch (e: Exception) {
            Log.e(TAG, "ADB relay send failed: ${e.message}")
        }
    }

    fun isConnected(): Boolean {
        val s = socket
        return s != null && s.isConnected && !s.isClosed
    }

    fun disconnect() {
        try { writer?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        writer = null
    }

    companion object {
        private const val TAG = "HoopLandHelper"
    }
}
