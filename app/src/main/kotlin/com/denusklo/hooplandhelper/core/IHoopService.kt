package com.denusklo.hooplandhelper.core

interface IHoopService {
    fun dispatchHoldGesture(x: Int, y: Int, durationMs: Long)
    fun cancelHoldGesture()
}
