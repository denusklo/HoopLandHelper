package com.denusklo.hooplandhelper.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.denusklo.hooplandhelper.core.IHoopService

class HoopAccessibilityService : AccessibilityService(), IHoopService {

    companion object {
        var instance: HoopAccessibilityService? = null
            private set
        private const val TAG = "HoopLandHelper"
    }

    private var holdX = 0f
    private var holdY = 0f

    override fun onServiceConnected() {
        instance = this
        Log.d(TAG, "HoopAccessibilityService CONNECTED")
    }

    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun dispatchHoldGesture(x: Int, y: Int, durationMs: Long) {
        holdX = x.toFloat()
        holdY = y.toFloat()
        Log.d(TAG, "dispatchHoldGesture at ($x,$y) for ${durationMs}ms")
        val path = Path().apply {
            moveTo(holdX, holdY)
            lineTo(holdX + 1f, holdY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Hold gesture completed naturally")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Hold gesture cancelled (release)")
            }
        }, null)
        if (!dispatched) {
            Log.e(TAG, "dispatchGesture returned false!")
        }
    }

    override fun cancelHoldGesture() {
        Log.d(TAG, "cancelHoldGesture — dispatching cancel tap")
        // Dispatching any new gesture cancels the in-progress hold
        val path = Path().apply { moveTo(1f, 1f) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 1)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }
}
