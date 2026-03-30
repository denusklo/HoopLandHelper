package com.denusklo.hooplandhelper.utils

class RootChecker(private val runSuId: () -> String = ::defaultRunSuId) {

    fun isRooted(): Boolean = try {
        runSuId().contains("uid=0")
    } catch (e: Exception) {
        false
    }
}

private fun defaultRunSuId(): String {
    val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
    val result = process.inputStream.bufferedReader().readLine() ?: ""
    process.destroy()
    return result
}
