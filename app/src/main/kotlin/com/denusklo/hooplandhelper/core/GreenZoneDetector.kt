package com.denusklo.hooplandhelper.core

import android.graphics.Color
import android.util.Log
import com.denusklo.hooplandhelper.data.HsvRange

class GreenZoneDetector(
    private val greenHsv: HsvRange,
    private val isGreenPixel: (Int) -> Boolean = { pixel -> defaultIsGreen(pixel, greenHsv) },
    private val debugDir: String? = null
) {

    private var frameDumpCount = 0

    // Pending candidate — must stabilise 2 consecutive valid-width frames before promotion
    private var pendingGreenLeft = -1
    private var pendingGreenRight = -1
    private var pendingGreenWidth = 0
    private var pendingGreenStableCount = 0

    // Accepted cache — only filled after stable promotion; used for fallback/grace
    private var lastAcceptedGreenLeft = -1
    private var lastAcceptedGreenRight = -1
    private var lastAcceptedGreenWidth = 0
    private var greenGraceFrames = 0
    private var oversizeFallbackFrames = 0

    // Pre-cursor suppression band — tracks green regions seen before cursor appears
    private var preCursorBandLeft = -1
    private var preCursorBandRight = -1
    private var preCursorBandFrames = 0

    /**
     * Result of analyzing a single bar frame.
     * @param cursorX  X position of the bright white cursor (-1 if not found)
     * @param greenLeft  Left edge of the green zone (-1 if not found)
     * @param greenRight  Right edge of the green zone (-1 if not found)
     */
    /** A contiguous green segment found during row scan. */
    private data class GreenSegment(val left: Int, val right: Int, val width: Int, val center: Int)

    /** Width range considered plausible for the real meter green zone. */
    private val PLAUSIBLE_WIDTH_RANGE = 6..40
    private val OVERSIZE_FALLBACK_LIMIT = 5

    data class BarAnalysis(
        val cursorX: Int,
        val greenLeft: Int,
        val greenRight: Int
    ) {
        val greenCenter: Int get() = (greenLeft + greenRight) / 2
        val greenWidth: Int get() = greenRight - greenLeft
        val hasGreenZone: Boolean get() = greenLeft >= 0 && greenRight > greenLeft
        val hasCursor: Boolean get() = cursorX >= 0

        /**
         * Get the target X position within the green zone (default: 70% for margin).
         * This is where we want the cursor to be when we release.
         */
        fun getTargetX(greenZoneProgress: Float = 0.7f): Int {
            if (!hasGreenZone) return -1
            return greenLeft + (greenWidth * greenZoneProgress).toInt()
        }
    }

    /**
     * Analyze a bar frame to find the cursor position and green zone boundaries.
     * Scans the entire mid-row for:
     * - Brightest pixel (cursor, brightness > 600)
     * - Contiguous green-ish segment (green zone, excluding edge markers)
     */
    fun analyzeBar(width: Int, height: Int, getPixel: (Int, Int) -> Int): BarAnalysis {
        val midY = height / 2

        // Debug PNG saving disabled to reduce thermal load during gameplay
        // if (frameDumpCount < 1 && debugDir != null) {
        //     saveDebugPng(width, height, getPixel, frameDumpCount)
        //     frameDumpCount++
        // }

        // Find cursor (brightest pixel) and collect all green segments in one pass
        var maxBrightness = 0
        var cursorX = -1

        var inGreen = false
        var currentGreenStart = -1
        val segments = mutableListOf<GreenSegment>()

        for (x in 0 until width) {
            val p = getPixel(x, midY)
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val brightness = r + g + b

            // Track cursor (brightest pixel)
            if (brightness > maxBrightness) {
                maxBrightness = brightness
                cursorX = x
            }

            // Track green zone (contiguous green-ish pixels in the middle 90% of bar)
            val isGreen = isGreenPixel(p) || isGreenish(r, g, b)
            if (isGreen && x > width * 0.05 && x < width * 0.95) {
                if (!inGreen) {
                    currentGreenStart = x
                    inGreen = true
                }
            } else {
                if (inGreen) {
                    val segRight = x - 1
                    val segWidth = segRight - currentGreenStart
                    if (segWidth > 0) {
                        segments.add(GreenSegment(currentGreenStart, segRight, segWidth, (currentGreenStart + segRight) / 2))
                    }
                    inGreen = false
                }
            }
        }
        // Close last green block
        if (inGreen) {
            val segRight = width - 1
            val segWidth = segRight - currentGreenStart
            if (segWidth > 0) {
                segments.add(GreenSegment(currentGreenStart, segRight, segWidth, (currentGreenStart + segRight) / 2))
            }
        }

        // Cursor threshold
        if (maxBrightness <= 600) cursorX = -1

        // --- Merge nearby segments before candidate selection ---
        val mergedSegments = mergeNearbySegments(segments, maxGapPx = 4)
        if (segments.size != mergedSegments.size) {
            val rawDesc = segments.joinToString(", ") { "${it.left}..${it.right}(${it.width})" }
            val mergedDesc = mergedSegments.joinToString(", ") { "${it.left}..${it.right}(${it.width})" }
            Log.d(TAG, "GREEN_MERGED: raw=[$rawDesc], merged=[$mergedDesc]")
        }

        // --- Pre-cursor band update ---
        // Early phase = no accepted zone yet AND cursor not found OR still in left quarter of bar.
        // Fold only truly oversized (>40px) merged segments into a suppression band.
        val earlyPhase = lastAcceptedGreenWidth <= 0 && (cursorX < 0 || cursorX < width / 4)
        val storedPreCursorBandLeft = preCursorBandLeft
        val storedPreCursorBandRight = preCursorBandRight
        val storedPreCursorBandSupported = hasOversizeSupportForBand(
            mergedSegments = mergedSegments,
            bandLeft = storedPreCursorBandLeft,
            bandRight = storedPreCursorBandRight
        )
        if (
            earlyPhase &&
            lastAcceptedGreenWidth <= 0 &&
            cursorX >= 0 &&
            preCursorBandLeft >= 0 &&
            !storedPreCursorBandSupported
        ) {
            preCursorBandLeft = -1
            preCursorBandRight = -1
            preCursorBandFrames = 0
            Log.d(TAG, "GREEN_PRECURSOR_BAND_EXPIRED: reason=current_support_lost")
        }
        if (earlyPhase) {
            val prevLeft = preCursorBandLeft
            val prevRight = preCursorBandRight
            val wasEmpty = preCursorBandLeft < 0
            for (seg in mergedSegments) {
                if (seg.width > 40) {
                    if (preCursorBandLeft < 0) {
                        preCursorBandLeft = seg.left
                        preCursorBandRight = seg.right
                    } else {
                        if (seg.left < preCursorBandLeft) preCursorBandLeft = seg.left
                        if (seg.right > preCursorBandRight) preCursorBandRight = seg.right
                    }
                }
            }
            if (preCursorBandLeft >= 0) {
                preCursorBandFrames++
                val boundsChanged = preCursorBandLeft != prevLeft || preCursorBandRight != prevRight
                if (wasEmpty || boundsChanged || preCursorBandFrames <= 3) {
                    Log.d(TAG, "GREEN_PRECURSOR_BAND: band=${preCursorBandLeft}..${preCursorBandRight}, frames=${preCursorBandFrames}")
                }
            }
        } else if (lastAcceptedGreenWidth <= 0 && preCursorBandLeft >= 0) {
            preCursorBandLeft = -1
            preCursorBandRight = -1
            preCursorBandFrames = 0
            Log.d(TAG, "GREEN_PRECURSOR_BAND_EXPIRED: reason=left_early_phase")
        }
        val currentPreCursorBandSupported = hasOversizeSupportForBand(
            mergedSegments = mergedSegments,
            bandLeft = preCursorBandLeft,
            bandRight = preCursorBandRight
        )

        // --- Wide merged-zone diagnostic ---
        val widestOversized = mergedSegments.filter { it.width > 40 }.maxByOrNull { it.width }
        if (widestOversized != null) {
            Log.d(TAG, "GREEN_WIDE_MERGED: seg=${widestOversized.left}..${widestOversized.right}(${widestOversized.width}), cursorX=$cursorX, earlyPhase=$earlyPhase")
        }

        // --- Candidate selection from merged segments ---
        val plausibleCandidates = mergedSegments.filter { it.width in PLAUSIBLE_WIDTH_RANGE }
        val chosen: GreenSegment? = when {
            plausibleCandidates.isNotEmpty() -> {
                if (lastAcceptedGreenWidth > 0) {
                    // Have accepted cache — pick closest right-edge match, then center, then narrower
                    val cachedRight = lastAcceptedGreenRight
                    val cachedCenter = (lastAcceptedGreenLeft + lastAcceptedGreenRight) / 2
                    plausibleCandidates.sortedWith(
                        compareBy(
                            { Math.abs(it.right - cachedRight) },
                            { Math.abs(it.center - cachedCenter) },
                            { it.width }
                        )
                    ).first()
                } else {
                    // No cache yet — prefer right-anchored, narrowest candidate
                    val maxRight = plausibleCandidates.maxOf { it.right }
                    val rightAnchored = plausibleCandidates.filter { it.right >= maxRight - 4 }
                    rightAnchored.sortedWith(
                        compareBy({ it.width }, { -it.left })
                    ).first()
                }
            }
            mergedSegments.isNotEmpty() -> {
                // No plausible-width segment — pick the best oversize segment (widest)
                mergedSegments.maxByOrNull { it.width }
            }
            else -> null
        }

        if (plausibleCandidates.size > 1) {
            val desc = plausibleCandidates.joinToString(", ") { "${it.left}..${it.right}(${it.width})" }
            val ch = chosen?.let { "${it.left}..${it.right}(${it.width})" } ?: "none"
            val mode = if (lastAcceptedGreenWidth > 0) "cached" else "first_acquire"
            Log.d(TAG, "GREEN_SELECT_RULE: mode=$mode, chosen=$ch, candidates=[$desc]")
        } else if (mergedSegments.size > 1) {
            val desc = mergedSegments.joinToString(", ") { "${it.left}..${it.right}(${it.width})" }
            val ch = chosen?.let { "${it.left}..${it.right}(${it.width})" } ?: "none"
            Log.d(TAG, "GREEN_CANDIDATES: count=${mergedSegments.size}, chosen=$ch, candidates=[$desc]")
        }

        val detectedGreenLeft = chosen?.left ?: -1
        val detectedGreenRight = chosen?.right ?: -1
        val detectedGreenWidth = chosen?.width ?: 0

        val acceptedGreenLeft: Int
        val acceptedGreenRight: Int

        // --- First-acquisition pre-cursor band suppression ---
        // If no accepted zone exists yet and the candidate overlaps the pre-cursor
        // band (seen >=2 frames), reject this candidate entirely for this frame.
        val suppressedByPreCursor = (
            earlyPhase &&
            lastAcceptedGreenWidth <= 0 &&
            detectedGreenWidth in 1..40 &&
            detectedGreenLeft >= 0 &&
            preCursorBandLeft >= 0 &&
            preCursorBandFrames >= 2 &&
            currentPreCursorBandSupported
        ) && run {
            val overlapPx = Math.min(detectedGreenRight, preCursorBandRight) -
                Math.max(detectedGreenLeft, preCursorBandLeft) + 1
            if (overlapPx >= 6) {
                Log.d(TAG, "GREEN_REJECT_PRECURSOR_OVERLAP: candidate=${detectedGreenLeft}..${detectedGreenRight}(${detectedGreenWidth}), band=${preCursorBandLeft}..${preCursorBandRight}, overlapPx=$overlapPx")
                true
            } else false
        }

        if (suppressedByPreCursor) {
            return BarAnalysis(cursorX = cursorX, greenLeft = -1, greenRight = -1)
        }

        if (detectedGreenWidth in 1..40 && detectedGreenLeft >= 0) {
            // --- Valid-width raw detection ---
            val rawCenter = (detectedGreenLeft + detectedGreenRight) / 2
            val pendingCenter = if (pendingGreenWidth > 0) (pendingGreenLeft + pendingGreenRight) / 2 else -1
            val sameCandidate = pendingGreenWidth > 0 &&
                Math.abs(rawCenter - pendingCenter) < 6 &&
                Math.abs(detectedGreenWidth - pendingGreenWidth) < 4

            if (sameCandidate) {
                pendingGreenStableCount++
                if (pendingGreenStableCount >= 2) {
                    // Stable for 2+ frames — promote to accepted cache
                    lastAcceptedGreenLeft = pendingGreenLeft
                    lastAcceptedGreenRight = pendingGreenRight
                    lastAcceptedGreenWidth = pendingGreenWidth
                    greenGraceFrames = 0
                    oversizeFallbackFrames = 0
                    Log.d(TAG, "GREEN_ACCEPTED: green=${pendingGreenLeft}..${pendingGreenRight}(${pendingGreenWidth}px)")
                } else {
                    Log.d(TAG, "GREEN_PENDING: raw=${detectedGreenLeft}..${detectedGreenRight}(${detectedGreenWidth}px), stableCount=${pendingGreenStableCount}")
                }
            } else {
                // New or different candidate — start pending
                pendingGreenLeft = detectedGreenLeft
                pendingGreenRight = detectedGreenRight
                pendingGreenWidth = detectedGreenWidth
                pendingGreenStableCount = 1
                Log.d(TAG, "GREEN_PENDING: raw=${detectedGreenLeft}..${detectedGreenRight}(${detectedGreenWidth}px), stableCount=1")
            }

            // Only expose to ShotManager once promoted to accepted cache
            if (pendingGreenStableCount >= 2) {
                acceptedGreenLeft = lastAcceptedGreenLeft
                acceptedGreenRight = lastAcceptedGreenRight
            } else {
                acceptedGreenLeft = -1
                acceptedGreenRight = -1
            }
        } else if (detectedGreenWidth > 40 && detectedGreenLeft >= 0) {
            // --- Oversized raw detection ---
            if (lastAcceptedGreenWidth > 0 && oversizeFallbackFrames < OVERSIZE_FALLBACK_LIMIT) {
                // Fallback to accepted cache when oversize remains continuous with it.
                val cachedCenter = (lastAcceptedGreenLeft + lastAcceptedGreenRight) / 2
                val rawCenter = (detectedGreenLeft + detectedGreenRight) / 2
                val overlap = detectedGreenLeft <= lastAcceptedGreenRight && detectedGreenRight >= lastAcceptedGreenLeft
                val nearby = Math.abs(rawCenter - cachedCenter) < 16
                if (overlap || nearby) {
                    oversizeFallbackFrames++
                    greenGraceFrames = 0
                    acceptedGreenLeft = lastAcceptedGreenLeft
                    acceptedGreenRight = lastAcceptedGreenRight
                    Log.d(TAG, "GREEN_FALLBACK_LAST_ACCEPTED: raw=${detectedGreenLeft}..${detectedGreenRight}(${detectedGreenWidth}px), cached=${lastAcceptedGreenLeft}..${lastAcceptedGreenRight}, reason=oversize, fallback=$oversizeFallbackFrames/$OVERSIZE_FALLBACK_LIMIT")
                } else {
                    // Reset pending — don't let a briefly narrow slice of this region promote later
                    pendingGreenLeft = -1
                    pendingGreenRight = -1
                    pendingGreenWidth = 0
                    pendingGreenStableCount = 0
                    acceptedGreenLeft = -1
                    acceptedGreenRight = -1
                    Log.d(TAG, "GREEN_REJECT_WIDTH: ${detectedGreenWidth}px [${detectedGreenLeft}..${detectedGreenRight}], no cached overlap")
                }
            } else if (lastAcceptedGreenWidth > 0 && oversizeFallbackFrames >= OVERSIZE_FALLBACK_LIMIT) {
                // Hard limit expired — invalidate accepted cache so it can't resurrect via grace
                pendingGreenLeft = -1
                pendingGreenRight = -1
                pendingGreenWidth = 0
                pendingGreenStableCount = 0
                lastAcceptedGreenLeft = -1
                lastAcceptedGreenRight = -1
                lastAcceptedGreenWidth = 0
                greenGraceFrames = 0
                acceptedGreenLeft = -1
                acceptedGreenRight = -1
                Log.d(TAG, "GREEN_CACHE_INVALIDATED: reason=oversize_expired, fallback=$oversizeFallbackFrames")
            } else {
                // No accepted cache at all
                pendingGreenLeft = -1
                pendingGreenRight = -1
                pendingGreenWidth = 0
                pendingGreenStableCount = 0
                acceptedGreenLeft = -1
                acceptedGreenRight = -1
                Log.d(TAG, "GREEN_REJECT_WIDTH: ${detectedGreenWidth}px [${detectedGreenLeft}..${detectedGreenRight}], no cache")
            }
        } else {
            // --- No green block found at all ---
            // Only reuse truly accepted cache, not pending candidates
            if (lastAcceptedGreenWidth > 0 && greenGraceFrames < 3) {
                greenGraceFrames++
                acceptedGreenLeft = lastAcceptedGreenLeft
                acceptedGreenRight = lastAcceptedGreenRight
                Log.d(TAG, "GREEN_FALLBACK_LAST_ACCEPTED: grace=$greenGraceFrames/3, cached=${lastAcceptedGreenLeft}..${lastAcceptedGreenRight}, reason=grace")
            } else if (lastAcceptedGreenWidth > 0) {
                acceptedGreenLeft = -1
                acceptedGreenRight = -1
                Log.d(TAG, "GREEN_FALLBACK_EXPIRED: reason=grace, grace=$greenGraceFrames")
            } else {
                acceptedGreenLeft = -1
                acceptedGreenRight = -1
            }
        }

        // --- Diagnostic multi-row scan (logging-only, does not affect detection) ---
        val willReturnNoGreenWithCursor = cursorX >= 0 && acceptedGreenLeft < 0
        val hasWideOnMain = mergedSegments.any { it.width > 40 }
        if (earlyPhase || hasWideOnMain || willReturnNoGreenWithCursor) {
            val row40 = (height * 40) / 100
            val row50 = height / 2
            val row60 = (height * 60) / 100
            val s40 = scanRowGreenSegments(width, row40, getPixel)
            val s50 = scanRowGreenSegments(width, row50, getPixel)
            val s60 = scanRowGreenSegments(width, row60, getPixel)
            fun fmt(segs: List<GreenSegment>): String =
                if (segs.isEmpty()) "none"
                else segs.joinToString(" ") { "${it.left}..${it.right}(${it.width})" }
            Log.d(TAG, "GREEN_ROW_SCAN: cursorX=$cursorX, rows=[40: ${fmt(s40)} | 50: ${fmt(s50)} | 60: ${fmt(s60)}]")
        }

        return BarAnalysis(
            cursorX = cursorX,
            greenLeft = acceptedGreenLeft,
            greenRight = acceptedGreenRight
        )
    }

    /**
     * Diagnostic: collect contiguous green segments on an arbitrary row using
     * the same green criteria as the main scan. Does not affect detection.
     */
    private fun scanRowGreenSegments(width: Int, y: Int, getPixel: (Int, Int) -> Int): List<GreenSegment> {
        val segments = mutableListOf<GreenSegment>()
        var inGreen = false
        var currentGreenStart = -1
        for (x in 0 until width) {
            val p = getPixel(x, y)
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val isGreen = isGreenPixel(p) || isGreenish(r, g, b)
            if (isGreen && x > width * 0.05 && x < width * 0.95) {
                if (!inGreen) {
                    currentGreenStart = x
                    inGreen = true
                }
            } else {
                if (inGreen) {
                    val segRight = x - 1
                    val segWidth = segRight - currentGreenStart
                    if (segWidth > 0) {
                        segments.add(GreenSegment(currentGreenStart, segRight, segWidth, (currentGreenStart + segRight) / 2))
                    }
                    inGreen = false
                }
            }
        }
        if (inGreen) {
            val segRight = width - 1
            val segWidth = segRight - currentGreenStart
            if (segWidth > 0) {
                segments.add(GreenSegment(currentGreenStart, segRight, segWidth, (currentGreenStart + segRight) / 2))
            }
        }
        return mergeNearbySegments(segments, maxGapPx = 4)
    }

    private fun hasOversizeSupportForBand(
        mergedSegments: List<GreenSegment>,
        bandLeft: Int,
        bandRight: Int
    ): Boolean {
        if (bandLeft < 0 || bandRight < bandLeft) return false
        val bandCenter = (bandLeft + bandRight) / 2
        return mergedSegments.any { seg ->
            seg.width > 40 && (
                (seg.left <= bandRight && seg.right >= bandLeft) ||
                    Math.abs(seg.center - bandCenter) < 16
                )
        }
    }

    /**
     * Merge green segments whose inter-segment gap is <= maxGapPx.
     * gap = next.left - current.right - 1
     * Prevents a single oversized artifact split by tiny non-green gaps from
     * appearing as multiple plausible-width candidates.
     */
    private fun mergeNearbySegments(segments: List<GreenSegment>, maxGapPx: Int = 4): List<GreenSegment> {
        if (segments.size < 2) return segments
        val sorted = segments.sortedBy { it.left }
        val merged = mutableListOf<GreenSegment>()
        var curLeft = sorted[0].left
        var curRight = sorted[0].right
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            val gap = next.left - curRight - 1
            if (gap <= maxGapPx) {
                if (next.right > curRight) curRight = next.right
                if (next.left < curLeft) curLeft = next.left
            } else {
                merged.add(GreenSegment(curLeft, curRight, curRight - curLeft, (curLeft + curRight) / 2))
                curLeft = next.left
                curRight = next.right
            }
        }
        merged.add(GreenSegment(curLeft, curRight, curRight - curLeft, (curLeft + curRight) / 2))
        return merged
    }

    /**
     * Check if RGB values look greenish (hue 90-170, enough green dominance).
     * This is a broader check than isGreenPixel to catch the Hoop Land green zone
     * which uses colors like #387846 (RGB 56,120,70).
     */
    private fun isGreenish(r: Int, g: Int, b: Int): Boolean {
        // Green channel should be dominant
        if (g <= r || g <= b) return false
        // Minimum green value to filter out dark noise
        if (g < 40) return false
        // Calculate rough hue — green is around 90-170 degrees
        // Simple check: green significantly > red and blue
        val ratio = g.toFloat() / (r + b + 1)
        return ratio > 1.3f
    }

    /**
     * Calculate the time in ms from the first frame to when cursor reaches the target position in green zone.
     * @param first First frame analysis (cursor + green zone)
     * @param second Second frame analysis (for cursor speed measurement)
     * @param intervalMs Time between the two frames
     * @param greenZoneProgress Target position in green zone (0.0 = left edge, 0.5 = center, 0.7 = 70%, 1.0 = right edge)
     * @return Time in ms until cursor reaches target position, or -1 if invalid
     */
    fun calculateTimeToGreenMs(
        first: BarAnalysis,
        second: BarAnalysis,
        intervalMs: Long,
        greenZoneProgress: Float = 0.7f
    ): Long {
        if (!first.hasGreenZone || !first.hasCursor || !second.hasCursor) return -1

        // Cursor speed in pixels per ms
        val dx = second.cursorX - first.cursorX
        if (dx <= 0) return -1  // cursor should be moving right
        val speed = dx.toFloat() / intervalMs.toFloat()

        // Target position (e.g., 70% into the green zone for margin)
        val targetX = first.getTargetX(greenZoneProgress)

        // Distance from first cursor position to target
        val distance = targetX - first.cursorX
        if (distance <= 0) return 0  // already at or past target

        // Time to reach target
        return (distance / speed).toLong()
    }

    /**
     * Legacy method — kept for backwards compatibility with tests.
     */
    fun isGreenZoneAtCursor(width: Int, height: Int, getPixel: (Int, Int) -> Int): Boolean {
        val cursorX = findCursorX(width, height, getPixel)
        if (cursorX < 0) return false
        return isSampleGreen(height, cursorX, getPixel)
    }

    private fun findCursorX(width: Int, height: Int, getPixel: (Int, Int) -> Int): Int {
        val midY = height / 2
        var maxBrightness = 0
        var cursorX = -1
        for (x in 0 until width) {
            val p = getPixel(x, midY)
            val brightness = ((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)
            if (brightness > maxBrightness) {
                maxBrightness = brightness
                cursorX = x
            }
        }
        return if (maxBrightness > 600) cursorX else -1
    }

    private fun isSampleGreen(height: Int, cursorX: Int, getPixel: (Int, Int) -> Int): Boolean {
        val midY = height / 2
        var foundGreen = false
        for (dx in -5..5) {
            val x = (cursorX + dx).coerceAtLeast(0)
            val pixel = getPixel(x, midY)
            if (isGreenPixel(pixel)) foundGreen = true
        }
        return foundGreen
    }

    /** Save frame as debug PNG — no text dump to keep logs clean */
    private fun saveDebugPng(width: Int, height: Int, getPixel: (Int, Int) -> Int, index: Int) {
        try {
            val dir = java.io.File(debugDir!!)
            if (!dir.exists()) dir.mkdirs()
            val file = java.io.File(dir, "bar_frame_${String.format("%02d", index)}.png")
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, getPixel(x, y))
                }
            }
            file.outputStream().use { fos ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos)
            }
            Log.d(TAG, "Debug frame saved: bar_frame_${String.format("%02d", index)}.png (${width}x${height})")
        } catch (e: Exception) {
            Log.e(TAG, "saveDebugPng failed: ${e.message}")
        }
    }

    fun resetDebug() {
        frameDumpCount = 0
        pendingGreenLeft = -1
        pendingGreenRight = -1
        pendingGreenWidth = 0
        pendingGreenStableCount = 0
        lastAcceptedGreenLeft = -1
        lastAcceptedGreenRight = -1
        lastAcceptedGreenWidth = 0
        greenGraceFrames = 0
        oversizeFallbackFrames = 0
        preCursorBandLeft = -1
        preCursorBandRight = -1
        preCursorBandFrames = 0
    }
    fun saveReleaseFrame(width: Int, height: Int, getPixel: (Int, Int) -> Int, cursorX: Int, targetX: Int) {
        if (debugDir == null) return
        try {
            val dir = java.io.File(debugDir)
            if (!dir.exists()) dir.mkdirs()
            val ts = System.currentTimeMillis()
            val file = java.io.File(dir, "release_$ts.png")
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, getPixel(x, y))
                }
            }
            file.outputStream().use { fos ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos)
            }
            Log.d(TAG, "Release frame: cursor=$cursorX, target=$targetX → saved release_$ts.png")
        } catch (e: Exception) {
            Log.e(TAG, "saveReleaseFrame failed: ${e.message}")
        }
    }
}

private const val TAG = "HoopLandHelper"

private fun defaultIsGreen(pixel: Int, greenHsv: HsvRange): Boolean {
    val hsv = FloatArray(3)
    Color.colorToHSV(pixel, hsv)
    val hueDiff = Math.abs(hsv[0] - greenHsv.hue).let { if (it > 180f) 360f - it else it }
    return hueDiff <= greenHsv.hueTolerance && hsv[1] >= greenHsv.satMin && hsv[2] >= greenHsv.valMin
}
