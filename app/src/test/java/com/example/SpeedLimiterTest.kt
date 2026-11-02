package com.example

import com.example.data.download.SpeedLimiter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedLimiterTest {
    @Test fun noLimitDoesNotWait() = runBlocking {
        val limiter = SpeedLimiter({ 0L })
        val start = System.currentTimeMillis()
        repeat(100) { limiter.acquire(64 * 1024) }
        assertTrue(System.currentTimeMillis() - start < 200)
    }

    @Test fun limitSlowsDownToTheRate() = runBlocking {
        val limiter = SpeedLimiter({ 512L * 1024 }) // 512 KB/s
        val start = System.currentTimeMillis()
        repeat(24) { limiter.acquire(64 * 1024) }   // 1.5 MB
        val ms = System.currentTimeMillis() - start
        // 1.5 MB at 512 KB/s should take about 3 s (first second may be a burst).
        assertTrue("took $ms ms", ms in 2400..4500)
    }
}
