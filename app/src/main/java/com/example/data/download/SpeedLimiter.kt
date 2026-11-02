package com.example.data.download

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the total download speed of all running downloads under a limit (Settings).
 * Every download asks for permission before writing a chunk; when they're going too
 * fast, they wait a moment. A limit of 0 means no limit.
 */
class SpeedLimiter(
    private val limitBytesPerSec: () -> Long,
    private val nanoTime: () -> Long = System::nanoTime
) {
    private val mutex = Mutex()
    private var available = 0.0
    private var lastRefill = nanoTime()

    suspend fun acquire(bytes: Int) {
        val limit = limitBytesPerSec()
        if (limit <= 0 || bytes <= 0) return
        val waitMs = mutex.withLock {
            val now = nanoTime()
            val elapsedSec = (now - lastRefill) / 1_000_000_000.0
            lastRefill = now
            // Allow at most one second of "saved up" speed, so bursts stay small.
            available = minOf(limit.toDouble(), available + elapsedSec * limit) - bytes
            if (available < 0) (-available * 1000 / limit).toLong() else 0L
        }
        if (waitMs > 0) delay(waitMs)
    }
}
