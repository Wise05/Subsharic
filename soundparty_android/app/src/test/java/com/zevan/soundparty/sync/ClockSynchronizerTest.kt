package com.zevan.soundparty.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockSynchronizerTest {

    @Test
    fun testInitialStateIsUnsynchronized() {
        val synchronizer = ClockSynchronizer(sendPing = { true }, monotonicTimeProvider = { 0L })
        val state = synchronizer.syncState.value

        assertFalse(state.isSynchronized)
        assertEquals(0L, state.theta)
        assertEquals(0, state.sampleCount)
    }

    @Test
    fun testSuccessfulSyncUpdatesThetaUsingMonotonicTime() = runBlocking {
        var mockMonotonic = 100_000L // e.g. 100 seconds after boot
        val mockServerEpochBase = 1_700_000_000_000L // arbitrary server epoch ms

        lateinit var synchronizer: ClockSynchronizer
        synchronizer = ClockSynchronizer(
            sendPing = { clientTx ->
                // Simulate network latency:
                // 10ms forward trip, 2ms server processing, 10ms return trip = 20ms RTT
                val serverRx = mockServerEpochBase + (clientTx - 100_000L) + 10L
                val serverTx = serverRx + 2L

                // Advance client time to receive time
                mockMonotonic += 22L
                synchronizer.onSyncPongReceived(clientTx, serverRx, serverTx)
                true
            },
            burstCount = 3,
            pingIntervalMs = 10L,
            pingTimeoutMs = 500L,
            monotonicTimeProvider = { mockMonotonic }
        )

        val bestSample = synchronizer.syncNow()

        assertNotNull(bestSample)
        assertTrue(synchronizer.syncState.value.isSynchronized)
        assertEquals(1, synchronizer.syncState.value.sampleCount)

        // Verify ServerTime = elapsedRealtime + θ
        val calculatedServerTime = synchronizer.currentServerTimeMillis()
        val expectedServerTime = mockServerEpochBase + (mockMonotonic - 100_000L)

        // Tolerance within +/- 5ms of expected server epoch
        assertTrue(
            "Calculated server time $calculatedServerTime should be close to $expectedServerTime",
            Math.abs(calculatedServerTime - expectedServerTime) < 5L
        )
    }

    @Test
    fun testMedianAndEmaFilterOutliers() = runBlocking {
        var mockMonotonic = 50_000L
        val mockServerEpochBase = 1_700_000_000_000L

        lateinit var synchronizer: ClockSynchronizer
        var pingCount = 0

        synchronizer = ClockSynchronizer(
            sendPing = { clientTx ->
                pingCount++
                // Introduce an artificial massive outlier on the 3rd ping
                val delay = if (pingCount == 3) 500L else 10L
                val serverRx = mockServerEpochBase + (clientTx - 50_000L) + delay
                val serverTx = serverRx + 1L

                mockMonotonic += (delay * 2)
                synchronizer.onSyncPongReceived(clientTx, serverRx, serverTx)
                true
            },
            burstCount = 5,
            windowSize = 5,
            pingIntervalMs = 5L,
            monotonicTimeProvider = { mockMonotonic }
        )

        val sample = synchronizer.syncNow()
        assertNotNull(sample)

        // The outlier RTT of ~1000ms should NOT be selected as the best burst sample
        assertTrue("Best sample RTT should be around 20ms, was ${sample!!.rttMs}", sample.rttMs < 50L)
    }

    @Test
    fun testFailedOrDroppedPingsDoNotCorruptSyncState() = runBlocking {
        val synchronizer = ClockSynchronizer(
            sendPing = { false }, // Transport fails to send
            burstCount = 2,
            pingIntervalMs = 10L,
            pingTimeoutMs = 50L,
            monotonicTimeProvider = { 1_000L }
        )

        val result = synchronizer.syncNow()

        assertEquals(null, result)
        assertFalse(synchronizer.syncState.value.isSynchronized)
    }
}
