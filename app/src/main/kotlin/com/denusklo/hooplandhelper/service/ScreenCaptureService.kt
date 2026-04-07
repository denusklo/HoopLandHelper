package com.denusklo.hooplandhelper.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.denusklo.hooplandhelper.data.BarRegion
import java.io.File
import java.nio.ByteBuffer

class ScreenCaptureService(private val context: Context) {

    companion object {
        private const val TAG = "HoopLandHelper"
        var instance: ScreenCaptureService? = null
            private set
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var barRegion: BarRegion? = null

    // Natural portrait dimensions
    private var naturalWidth = 0
    private var naturalHeight = 0

    // Letterbox offset: game content row range in portrait buffer
    private var contentRowStart = -1
    private var contentRowEnd = -1
    private var contentHeight = 0
    private var contentScanned = false

    // Debug PNG saving — separate from content scanning
    private var debugPngSaved = false

    // Latest frame cached by the image listener
    @Volatile
    private var latestFrame: FrameData? = null

    private data class FrameData(
        val buffer: ByteBuffer,
        val rowStride: Int,
        val pixelStride: Int,
        val bufferCap: Long,
        val rotation: Int
    )

    fun start(resultCode: Int, data: android.content.Intent, region: BarRegion) {
        barRegion = region

        val realMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        val display = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        display.getRealMetrics(realMetrics)
        naturalWidth = minOf(realMetrics.widthPixels, realMetrics.heightPixels)
        naturalHeight = maxOf(realMetrics.widthPixels, realMetrics.heightPixels)

        @Suppress("DEPRECATION")
        val rotation = display.rotation

        Log.d(TAG, "ScreenCapture started: naturalPortrait=${naturalWidth}x${naturalHeight}, rotation=$rotation, barRegion=(${region.left},${region.top},${region.right},${region.bottom})")

        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(resultCode, data)

        captureThread = HandlerThread("ScreenCapture").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)

        imageReader = ImageReader.newInstance(naturalWidth, naturalHeight, PixelFormat.RGBA_8888, 3)

        imageReader!!.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val srcBuffer = plane.buffer
                val cap = srcBuffer.capacity().toLong()

                val copy = ByteBuffer.allocate(srcBuffer.remaining())
                copy.put(srcBuffer)
                copy.flip()

                @Suppress("DEPRECATION")
                val rot = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                    .defaultDisplay.rotation

                latestFrame = FrameData(copy, rowStride, pixelStride, cap, rot)
            } catch (e: Exception) {
                Log.e(TAG, "Frame listener error: ${e.message}")
            } finally {
                image.close()
            }
        }, captureHandler)

        val density = context.resources.displayMetrics.densityDpi
        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "HoopCapture", naturalWidth, naturalHeight, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )
        instance = this
    }

    fun stop() {
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
        captureHandler?.removeCallbacksAndMessages(null)
        captureThread?.quitSafely()
        virtualDisplay = null
        mediaProjection = null
        imageReader = null
        captureThread = null
        captureHandler = null
        latestFrame = null
        instance = null
    }

    fun resetDebug() {
        // Don't reset contentScanned — content bounds are stable during gameplay.
        // Don't reset debugPngSaved — PNG I/O is too slow for the shot loop.
    }

    /**
     * Check if content bounds need rescanning.
     * Returns true if never scanned, or if cached bounds look like portrait (too tall).
     */
    private fun needsContentRescan(): Boolean {
        if (!contentScanned) return true
        if (contentRowStart < 0) return true
        // If content height > naturalWidth, we scanned in portrait mode — need landscape rescan
        if (contentHeight > naturalWidth) return true
        return false
    }

    /**
     * Scan portrait buffer for non-black rows to find the game content area.
     * Only saves debug PNG on the very first scan.
     */
    private fun scanContentBounds(
        buffer: ByteBuffer, rowStride: Int, pixelStride: Int, bufferCap: Long
    ) {
        var firstNonBlack = -1
        var lastNonBlack = -1
        val threshold = 15

        val sampleCols = intArrayOf(
            naturalWidth / 4, naturalWidth / 2, naturalWidth * 3 / 4
        )

        for (row in 0 until naturalHeight) {
            var rowHasContent = false
            for (col in sampleCols) {
                val offset = row.toLong() * rowStride + col.toLong() * pixelStride
                if (offset + 3 >= bufferCap) continue
                buffer.position(offset.toInt())
                val r = buffer.get().toInt() and 0xFF
                val g = buffer.get().toInt() and 0xFF
                val b = buffer.get().toInt() and 0xFF
                if (r > threshold || g > threshold || b > threshold) {
                    rowHasContent = true
                    break
                }
            }
            if (rowHasContent) {
                if (firstNonBlack < 0) firstNonBlack = row
                lastNonBlack = row
            }
        }

        contentRowStart = firstNonBlack
        contentRowEnd = lastNonBlack
        contentHeight = if (firstNonBlack >= 0 && lastNonBlack >= firstNonBlack) {
            lastNonBlack - firstNonBlack + 1
        } else {
            naturalHeight
        }
        contentScanned = true

        Log.d(TAG, "Content bounds: rows $firstNonBlack..$lastNonBlack (height=$contentHeight) in ${naturalWidth}x${naturalHeight} portrait buffer")

        // Save content strip PNG in background (slow, don't block shot loop)
        if (!debugPngSaved && firstNonBlack >= 0 && contentHeight > 0 && contentHeight < naturalHeight) {
            val bgBuffer = buffer.duplicate()
            val fnb = firstNonBlack
            val ch = contentHeight
            Thread {
                try {
                    val dir = File(context.getExternalFilesDir(null), "debug")
                    if (!dir.exists()) dir.mkdirs()
                    val bmp = Bitmap.createBitmap(naturalWidth, ch, Bitmap.Config.ARGB_8888)
                    for (row in 0 until ch) {
                        for (col in 0 until naturalWidth) {
                            val offset = (fnb + row).toLong() * rowStride + col.toLong() * pixelStride
                            if (offset + 3 >= bufferCap) continue
                            bgBuffer.position(offset.toInt())
                            val r = bgBuffer.get().toInt() and 0xFF
                            val g = bgBuffer.get().toInt() and 0xFF
                            val b = bgBuffer.get().toInt() and 0xFF
                            bmp.setPixel(col, row, android.graphics.Color.rgb(r, g, b))
                        }
                    }
                    val file = File(dir, "content_strip.png")
                    file.outputStream().use { fos ->
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
                    }
                    Log.d(TAG, "Saved content strip: ${file.absolutePath} (${naturalWidth}x${ch})")
                } catch (e: Exception) {
                    Log.e(TAG, "save content strip failed: ${e.message}")
                }
            }.start()
        }
    }

    /**
     * Map landscape display coordinates to portrait buffer coordinates.
     */
    private fun displayToBufferCoords(dx: Int, dy: Int): Pair<Int, Int> {
        if (contentRowStart < 0) {
            return dy to dx
        }

        val displayWidth = naturalHeight
        val displayHeight = naturalWidth

        val bufCol = (dx.toLong() * naturalWidth / displayWidth).toInt()
        val bufRow = contentRowStart + (dy.toLong() * contentHeight / displayHeight).toInt()

        return bufRow to bufCol
    }

    fun acquireBarFrame(): Triple<Int, Int, (Int, Int) -> Int>? {
        val region = barRegion ?: return null
        val frame = latestFrame ?: return null

        val buffer = frame.buffer.duplicate()
        val rowStride = frame.rowStride
        val pixelStride = frame.pixelStride
        val bufferCap = frame.bufferCap.toLong()

        // Rescan content bounds if needed (e.g., first landscape frame after portrait overlay check)
        // This is fast (~250ms) and only happens once when transitioning to landscape game.
        if (needsContentRescan()) {
            scanContentBounds(buffer, rowStride, pixelStride, bufferCap)
        }

        // Debug PNG saving disabled to reduce thermal load during gameplay
        // if (!debugPngSaved) {
        //     debugPngSaved = true
        //     val bgBuffer = buffer.duplicate()
        //     Thread {
        //         saveDebugPngs(bgBuffer, rowStride, pixelStride, bufferCap)
        //     }.start()
        // }

        val width = region.right - region.left
        val height = region.bottom - region.top
        if (width <= 0 || height <= 0) return null

        // Direct buffer reader — no bitmap allocation.
        // GreenZoneDetector only reads midY row (463 pixels instead of 29,169).
        return Triple(width, height) { x, y ->
            val dx = region.left + x
            val dy = region.top + y
            val (bufRow, bufCol) = displayToBufferCoords(dx, dy)
            val offset = bufRow.toLong() * rowStride + bufCol.toLong() * pixelStride
            if (offset < 0 || offset + 3 >= bufferCap) {
                0
            } else {
                buffer.position(offset.toInt())
                val r = buffer.get().toInt() and 0xFF
                val g = buffer.get().toInt() and 0xFF
                val b = buffer.get().toInt() and 0xFF
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    private fun saveDebugPngs(
        buffer: ByteBuffer, rowStride: Int, pixelStride: Int, bufferCap: Long
    ) {
        try {
            val dir = File(context.getExternalFilesDir(null), "debug")
            if (!dir.exists()) dir.mkdirs()

            val portraitBmp = Bitmap.createBitmap(naturalWidth, naturalHeight, Bitmap.Config.ARGB_8888)
            for (row in 0 until naturalHeight) {
                for (col in 0 until naturalWidth) {
                    val offset = row.toLong() * rowStride + col.toLong() * pixelStride
                    if (offset + 3 >= bufferCap) continue
                    buffer.position(offset.toInt())
                    val r = buffer.get().toInt() and 0xFF
                    val g = buffer.get().toInt() and 0xFF
                    val b = buffer.get().toInt() and 0xFF
                    portraitBmp.setPixel(col, row, android.graphics.Color.rgb(r, g, b))
                }
            }
            val portraitFile = File(dir, "full_portrait.png")
            portraitFile.outputStream().use { fos ->
                portraitBmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            Log.d(TAG, "Saved portrait screenshot: ${portraitFile.absolutePath} (${naturalWidth}x${naturalHeight})")

            logSamplePoints(buffer, rowStride, pixelStride, bufferCap)
        } catch (e: Exception) {
            Log.e(TAG, "saveDebugPngs failed: ${e.message}")
        }
    }

    private fun logSamplePoints(
        buffer: ByteBuffer, rowStride: Int, pixelStride: Int, bufferCap: Long
    ) {
        try {
            val region = barRegion ?: return
            val midY = (region.top + region.bottom) / 2
            val sb = StringBuilder()
            sb.append("Bar region debug: region=(${region.left},${region.top},${region.right},${region.bottom}), contentRows=$contentRowStart..$contentRowEnd, contentHeight=$contentHeight")
            for (x in region.left..region.right step (region.right - region.left).coerceAtLeast(1) / 10) {
                val (bufRow, bufCol) = displayToBufferCoords(x, midY)
                val offset = bufRow.toLong() * rowStride + bufCol.toLong() * pixelStride
                if (offset >= 0 && offset + 3 < bufferCap) {
                    buffer.position(offset.toInt())
                    val r = buffer.get().toInt() and 0xFF
                    val g = buffer.get().toInt() and 0xFF
                    val b = buffer.get().toInt() and 0xFF
                    sb.append("\n  display($x,$midY) -> buf($bufRow,$bufCol): RGB=($r,$g,$b)")
                } else {
                    sb.append("\n  display($x,$midY) -> buf($bufRow,$bufCol): OOB")
                }
            }
            Log.d(TAG, sb.toString())
        } catch (e: Exception) {
            Log.e(TAG, "logSamplePoints failed: ${e.message}")
        }
    }
}
