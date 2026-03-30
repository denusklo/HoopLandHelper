package com.denusklo.hooplandhelper.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import com.denusklo.hooplandhelper.core.IHoopService

class HoopAccessibilityService : AccessibilityService(), IHoopService {

    companion object {
        var instance: HoopAccessibilityService? = null
            private set
    }

    private val CHUNK_MS = 50L
    private var isHolding = false
    private var holdX = 0f
    private var holdY = 0f
    private var prevStroke: GestureDescription.StrokeDescription? = null

    override fun onServiceConnected() { instance = this }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun dispatchHoldGesture(x: Int, y: Int, durationMs: Long) {
        holdX = x.toFloat()
        holdY = y.toFloat()
        isHolding = true
        dispatchChunk(isFirst = true)
    }

    override fun cancelHoldGesture() {
        isHolding = false
    }

    private fun dispatchChunk(isFirst: Boolean) {
        if (!isHolding) return
        val path = Path().apply { moveTo(holdX, holdY) }
        val stroke = if (isFirst || prevStroke == null) {
            GestureDescription.StrokeDescription(path, 0, CHUNK_MS, true)
        } else {
            prevStroke!!.continueStroke(path, 0, CHUNK_MS, true)
        }
        prevStroke = stroke
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (isHolding) dispatchChunk(isFirst = false)
            }
        }, null)
    }
}
