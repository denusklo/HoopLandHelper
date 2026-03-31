package com.denusklo.hooplandhelper.utils

import android.util.Log

class RootChecker(private val runSuId: () -> String = ::defaultRunSuId) {

    fun isRooted(): Boolean = try {
        val output = runSuId()
        Log.d(TAG, "su -c id output: '$output'")
        output.contains("uid=0")
    } catch (e: Exception) {
        Log.w(TAG, "Root check failed: ${e.message}")
        false
    }

    companion object {
        private const val TAG = "HoopLandHelper"
    }
}

private fun defaultRunSuId(): String {
    val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
    val result = process.inputStream.bufferedReader().readLine() ?: ""
    process.destroy()
    return result
}
