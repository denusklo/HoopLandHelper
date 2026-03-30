package com.denusklo.hooplandhelper.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import com.denusklo.hooplandhelper.data.BarRegion

class ScreenCaptureService(private val context: Context) {

    companion object {
        var instance: ScreenCaptureService? = null
            private set
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var barRegion: BarRegion? = null

    fun start(resultCode: Int, data: android.content.Intent, region: BarRegion) {
        barRegion = region
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels

        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(resultCode, data)

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "HoopCapture", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )
        instance = this
    }

    fun stop() {
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
        virtualDisplay = null
        mediaProjection = null
        imageReader = null
        instance = null
    }

    fun acquireBarFrame(): Triple<Int, Int, (Int, Int) -> Int>? {
        val region = barRegion ?: return null
        val image = imageReader?.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val buffer = plane.buffer

            val width = region.right - region.left
            val height = region.bottom - region.top
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

            for (y in 0 until height) {
                for (x in 0 until width) {
                    val offset = (region.top + y) * rowStride + (region.left + x) * pixelStride
                    buffer.position(offset)
                    val r = buffer.get().toInt() and 0xFF
                    val g = buffer.get().toInt() and 0xFF
                    val b = buffer.get().toInt() and 0xFF
                    bitmap.setPixel(x, y, android.graphics.Color.rgb(r, g, b))
                }
            }
            Triple(width, height, bitmap::getPixel)
        } finally {
            image.close()
        }
    }
}
