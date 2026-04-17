package com.denusklo.hooplandhelper.core

import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.locks.LockSupport
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

data class SleepTrace(
    val targetNs: Long,
    val coarseWakeNs: Long,
    val spinStartNs: Long,
    val wakeNs: Long,
    val wakeErrorNs: Long
)

interface AbsoluteTimeSleeper {
    suspend fun sleepUntil(targetNs: Long): SleepTrace
}

/**
 * Three-phase absolute-time sleeper for sub-millisecond wake precision.
 *
 * Phase 1: coarse Thread.sleep until [targetNs - coarseMarginNs]
 *          (proper millis/nanos split to avoid invalid arguments)
 * Phase 2: LockSupport.parkNanos until [targetNs - spinMarginNs]
 * Phase 3: busy-spin to targetNs
 *
 * Runs on a dedicated single-thread dispatcher to avoid starving other coroutines during spin.
 */
class JvmAbsoluteTimeSleeper(
    private val coarseMarginNs: Long = 2_000_000L,
    private val spinMarginNs: Long = 300_000L,
    dispatcher: CoroutineContext? = null
) : AbsoluteTimeSleeper, Closeable {

    private val ownedExecutor = if (dispatcher == null) {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "abs-sleeper").apply { isDaemon = true }
        }
    } else {
        null
    }

    private val dispatcher: CoroutineContext = dispatcher
        ?: ownedExecutor!!.asCoroutineDispatcher()

    @Volatile
    private var closed = false

    override suspend fun sleepUntil(targetNs: Long): SleepTrace {
        return withContext(dispatcher) {
            // Phase 1: coarse sleep until targetNs - coarseMarginNs
            val coarseTarget = targetNs - coarseMarginNs
            while (System.nanoTime() < coarseTarget && !closed) {
                val remaining = coarseTarget - System.nanoTime()
                if (remaining > 0) {
                    safeSleep(remaining)
                }
            }
            val coarseWakeNs = System.nanoTime()

            // Phase 2: fine park until targetNs - spinMarginNs
            val fineTarget = targetNs - spinMarginNs
            while (System.nanoTime() < fineTarget && !closed) {
                val remaining = fineTarget - System.nanoTime()
                if (remaining > 100_000L) {
                    LockSupport.parkNanos(remaining / 2)
                }
            }
            val spinStartNs = System.nanoTime()

            // Phase 3: busy-spin to target
            while (System.nanoTime() < targetNs && !closed) {
                // spin
            }

            val wakeNs = System.nanoTime()
            SleepTrace(targetNs, coarseWakeNs, spinStartNs, wakeNs, wakeNs - targetNs)
        }
    }

    /**
     * Sleep for [durationNs] nanoseconds using Thread.sleep with a safe millis/nanos split.
     * For sub-2ms durations, uses LockSupport.parkNanos instead to avoid Thread.sleep
     * granularity issues.
     */
    private fun safeSleep(durationNs: Long) {
        if (durationNs <= 0) return
        if (durationNs < 2_000_000L) {
            // Sub-2ms: use parkNanos for better precision
            LockSupport.parkNanos(durationNs)
        } else {
            // Safe split: millis + [0..999999] nanos
            val millis = durationNs / 1_000_000L
            val nanos = (durationNs % 1_000_000L).toInt()
            Thread.sleep(millis, nanos)
        }
    }

    override fun close() {
        closed = true
        ownedExecutor?.shutdown()
    }
}
