package com.denusklo.hooplandhelper.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import com.denusklo.hooplandhelper.core.BarFrame
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale

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
    private var barRegionDiagnosticsSaved = false

    // Frame sequence counter — increments per unique captured frame
    private var frameSeqCounter: Long = 0

    // Track last-returned timestamp for isDuplicate detection in acquireBarFrame
    private var lastReturnedTimestampNs: Long = 0L

    // Latest frame cached by the image listener
    @Volatile
    private var latestFrame: FrameData? = null

    // Frame deduplication: track last-seen timestamp to skip duplicate frames
    @Volatile
    private var lastCapturedTimestampNs: Long = 0L

    private data class FrameData(
        val buffer: ByteBuffer,
        val rowStride: Int,
        val pixelStride: Int,
        val bufferCap: Long,
        val rotation: Int,
        val timestampNs: Long,     // Image.timestamp (CLOCK_MONOTONIC nanoseconds)
        val frameSeq: Long,        // Sequential frame counter
        val observedNs: Long       // System.nanoTime() when cached
    )

    private data class RegionMapping(
        val topLeft: Pair<Int, Int>,
        val topRight: Pair<Int, Int>,
        val bottomLeft: Pair<Int, Int>,
        val bottomRight: Pair<Int, Int>,
        val center: Pair<Int, Int>
    )

    private data class RoiCaptureResult(
        val bitmap: Bitmap,
        val avgBrightness: Double,
        val minBrightness: Int,
        val maxBrightness: Int
    )

    fun start(resultCode: Int, data: android.content.Intent, region: BarRegion) {
        barRegion = region
        barRegionDiagnosticsSaved = false

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
                val frameTs = image.timestamp  // CLOCK_MONOTONIC nanoseconds

                // Deduplication: skip buffer copy if same frame (display 60Hz, game 30fps)
                if (frameTs == lastCapturedTimestampNs) {
                    return@setOnImageAvailableListener
                }
                lastCapturedTimestampNs = frameTs
                frameSeqCounter++

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

                latestFrame = FrameData(copy, rowStride, pixelStride, cap, rot, frameTs, frameSeqCounter, System.nanoTime())
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
        lastCapturedTimestampNs = 0L
        lastReturnedTimestampNs = 0L
        frameSeqCounter = 0
        barRegionDiagnosticsSaved = false
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

    fun acquireBarFrame(): BarFrame? {
        val region = barRegion ?: return null
        val frame = latestFrame ?: return null

        val buffer = frame.buffer.duplicate()
        val rowStride = frame.rowStride
        val pixelStride = frame.pixelStride
        val bufferCap = frame.bufferCap.toLong()

        // Dedup: check if this frame was already returned
        val isDup = (frame.timestampNs == lastReturnedTimestampNs)
        lastReturnedTimestampNs = frame.timestampNs

        // Rescan content bounds if needed (e.g., first landscape frame after portrait overlay check)
        // This is fast (~250ms) and only happens once when transitioning to landscape game.
        if (needsContentRescan()) {
            scanContentBounds(buffer, rowStride, pixelStride, bufferCap)
        }

        val width = region.right - region.left
        val height = region.bottom - region.top
        if (width <= 0 || height <= 0) return null

        maybeCaptureBarRegionDiagnostics(
            region = region,
            width = width,
            height = height,
            buffer = frame.buffer.duplicate(),
            rowStride = rowStride,
            pixelStride = pixelStride,
            bufferCap = bufferCap
        )

        // Direct buffer reader — no bitmap allocation.
        // GreenZoneDetector only reads midY row (463 pixels instead of 29,169).
        val getPixel = createBarRegionPixelReader(region, buffer, rowStride, pixelStride, bufferCap)

        return BarFrame(width, height, getPixel, frame.timestampNs, isDup, frame.frameSeq, frame.observedNs)
    }

    private fun maybeCaptureBarRegionDiagnostics(
        region: BarRegion,
        width: Int,
        height: Int,
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        bufferCap: Long
    ) {
        if (barRegionDiagnosticsSaved || !contentScanned || needsContentRescan()) return
        barRegionDiagnosticsSaved = true

        val mapping = mapRegionToBuffer(region, width, height)
        Log.d(
            TAG,
            "BAR_REGION_MAP: display=(${region.left},${region.top},${region.right},${region.bottom}), " +
                "bufferTL=${formatCoords(mapping.topLeft)}, " +
                "bufferTR=${formatCoords(mapping.topRight)}, " +
                "bufferBL=${formatCoords(mapping.bottomLeft)}, " +
                "bufferBR=${formatCoords(mapping.bottomRight)}, " +
                "bufferCenter=${formatCoords(mapping.center)}, " +
                "contentRows=$contentRowStart..$contentRowEnd, contentHeight=$contentHeight"
        )

        Thread {
            saveBarRegionDiagnostics(region, width, height, mapping, buffer, rowStride, pixelStride, bufferCap)
        }.start()
    }

    private fun createBarRegionPixelReader(
        region: BarRegion,
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        bufferCap: Long
    ): (Int, Int) -> Int {
        val readerBuffer = buffer.duplicate()
        return { x, y ->
            val dx = region.left + x
            val dy = region.top + y
            val (bufRow, bufCol) = displayToBufferCoords(dx, dy)
            readBufferPixel(readerBuffer, bufRow, bufCol, rowStride, pixelStride, bufferCap)
        }
    }

    private fun readBufferPixel(
        buffer: ByteBuffer,
        row: Int,
        col: Int,
        rowStride: Int,
        pixelStride: Int,
        bufferCap: Long
    ): Int {
        if (row < 0 || row >= naturalHeight || col < 0 || col >= naturalWidth) return 0
        val offset = row.toLong() * rowStride + col.toLong() * pixelStride
        if (offset < 0 || offset + 3 >= bufferCap) return 0
        buffer.position(offset.toInt())
        val r = buffer.get().toInt() and 0xFF
        val g = buffer.get().toInt() and 0xFF
        val b = buffer.get().toInt() and 0xFF
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun mapRegionToBuffer(region: BarRegion, width: Int, height: Int): RegionMapping {
        val sampleRight = region.left + width - 1
        val sampleBottom = region.top + height - 1
        val centerX = region.left + (width / 2)
        val centerY = region.top + (height / 2)
        return RegionMapping(
            topLeft = displayToBufferCoords(region.left, region.top),
            topRight = displayToBufferCoords(sampleRight, region.top),
            bottomLeft = displayToBufferCoords(region.left, sampleBottom),
            bottomRight = displayToBufferCoords(sampleRight, sampleBottom),
            center = displayToBufferCoords(centerX, centerY)
        )
    }

    private fun formatCoords(point: Pair<Int, Int>): String = "(${point.first},${point.second})"

    private fun saveBarRegionDiagnostics(
        region: BarRegion,
        width: Int,
        height: Int,
        mapping: RegionMapping,
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        bufferCap: Long
    ) {
        try {
            val dir = File(context.getExternalFilesDir(null), "debug")
            if (!dir.exists()) dir.mkdirs()

            val portraitBmp = createPortraitBitmap(buffer.duplicate(), rowStride, pixelStride, bufferCap)
            drawBarRegionOverlay(portraitBmp, mapping)
            val portraitFile = File(dir, "full_portrait_with_bar_region.png")
            portraitFile.outputStream().use { fos ->
                portraitBmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            portraitBmp.recycle()
            Log.d(
                TAG,
                "BAR_REGION_DEBUG_IMAGE: file=${portraitFile.absolutePath}, size=${naturalWidth}x${naturalHeight}"
            )

            val roiCapture = createBarRegionCapture(region, width, height, buffer.duplicate(), rowStride, pixelStride, bufferCap)
            val roiFile = File(dir, "bar_region_capture.png")
            roiFile.outputStream().use { fos ->
                roiCapture.bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            roiCapture.bitmap.recycle()
            Log.d(
                TAG,
                "BAR_REGION_DEBUG_IMAGE: file=${roiFile.absolutePath}, size=${width}x${height}"
            )
            Log.d(
                TAG,
                "BAR_REGION_STATS: size=${width}x${height}, " +
                    "avgBrightness=${String.format(Locale.US, "%.1f", roiCapture.avgBrightness)}, " +
                    "minBrightness=${roiCapture.minBrightness}, maxBrightness=${roiCapture.maxBrightness}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "BAR_REGION_DIAGNOSTICS_FAILED: ${e.message}")
        }
    }

    private fun createPortraitBitmap(
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        bufferCap: Long
    ): Bitmap {
        val portraitBmp = Bitmap.createBitmap(naturalWidth, naturalHeight, Bitmap.Config.ARGB_8888)
        for (row in 0 until naturalHeight) {
            for (col in 0 until naturalWidth) {
                portraitBmp.setPixel(
                    col,
                    row,
                    readBufferPixel(buffer, row, col, rowStride, pixelStride, bufferCap)
                )
            }
        }
        return portraitBmp
    }

    private fun drawBarRegionOverlay(bitmap: Bitmap, mapping: RegionMapping) {
        val canvas = Canvas(bitmap)
        val contentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.YELLOW
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        val roiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }
        val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.CYAN
            style = Paint.Style.FILL
        }

        if (contentRowStart >= 0 && contentRowEnd >= contentRowStart) {
            canvas.drawRect(
                0f,
                contentRowStart.toFloat(),
                (naturalWidth - 1).toFloat(),
                contentRowEnd.toFloat(),
                contentPaint
            )
        }

        drawMappedLine(canvas, mapping.topLeft, mapping.topRight, roiPaint)
        drawMappedLine(canvas, mapping.topRight, mapping.bottomRight, roiPaint)
        drawMappedLine(canvas, mapping.bottomRight, mapping.bottomLeft, roiPaint)
        drawMappedLine(canvas, mapping.bottomLeft, mapping.topLeft, roiPaint)

        val centerX = mapping.center.second.toFloat()
        val centerY = mapping.center.first.toFloat()
        canvas.drawCircle(centerX, centerY, 8f, centerPaint)
        canvas.drawLine(centerX - 16f, centerY, centerX + 16f, centerY, centerPaint)
        canvas.drawLine(centerX, centerY - 16f, centerX, centerY + 16f, centerPaint)
    }

    private fun drawMappedLine(
        canvas: Canvas,
        start: Pair<Int, Int>,
        end: Pair<Int, Int>,
        paint: Paint
    ) {
        canvas.drawLine(
            start.second.toFloat(),
            start.first.toFloat(),
            end.second.toFloat(),
            end.first.toFloat(),
            paint
        )
    }

    private fun createBarRegionCapture(
        region: BarRegion,
        width: Int,
        height: Int,
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        bufferCap: Long
    ): RoiCaptureResult {
        val getPixel = createBarRegionPixelReader(region, buffer, rowStride, pixelStride, bufferCap)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        var brightnessSum = 0L
        var minBrightness = 255
        var maxBrightness = 0

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = getPixel(x, y)
                bitmap.setPixel(x, y, pixel)
                val brightness = brightnessOf(pixel)
                brightnessSum += brightness.toLong()
                if (brightness < minBrightness) minBrightness = brightness
                if (brightness > maxBrightness) maxBrightness = brightness
            }
        }

        val pixelCount = (width * height).coerceAtLeast(1)
        return RoiCaptureResult(
            bitmap = bitmap,
            avgBrightness = brightnessSum.toDouble() / pixelCount.toDouble(),
            minBrightness = minBrightness,
            maxBrightness = maxBrightness
        )
    }

    private fun brightnessOf(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (299 * r + 587 * g + 114 * b) / 1000
    }
}
