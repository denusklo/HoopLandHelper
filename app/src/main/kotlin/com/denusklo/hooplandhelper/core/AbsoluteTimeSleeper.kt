package com.denusklo.hooplandhelper.core

import java.io.Closeable
import java.util.concurrent.locks.LockSupport
import kotlinx.coroutines.Dispatchers
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
 * Phase 2: LockSupport.parkNanos until [targetNs - spinMarginNs]
 * Phase 3: busy-spin to targetNs
 *
 * Runs on a dedicated dispatcher to avoid starving other coroutines during spin.
 */
class JvmAbsoluteTimeSleeper(
    private val coarseMarginNs: Long = 2_000_000L,
    private val spinMarginNs: Long = 300_000L,
    private val dispatcher: CoroutineContext = Dispatchers.Default
) : AbsoluteTimeSleeper, Closeable {

    @Volatile
    private var closed = false

    override suspend fun sleepUntil(targetNs: Long): SleepTrace {
        return withContext(dispatcher) {
            // Phase 1: coarse sleep until targetNs - coarseMarginNs
            val coarseTarget = targetNs - coarseMarginNs
            while (System.nanoTime() < coarseTarget && !closed) {
                val remaining = coarseTarget - System.nanoTime()
                if (remaining > 2_000_000L) {
                    Thread.sleep(remaining / 1_000_000L)
                } else if (remaining > 0) {
                    Thread.sleep(0, remaining.toInt())
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

    override fun close() {
        closed = true
    }
}
