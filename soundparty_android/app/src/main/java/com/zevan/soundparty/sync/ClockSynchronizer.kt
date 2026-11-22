package com.zevan.soundparty.sync

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Snapshot of a single NTP timestamp exchange using monotonic time baseline.
 *
 * @property t0ClientElapsed Monotonic client send time (SystemClock.elapsedRealtime)
 * @property t1ServerRx Server receive time in epoch milliseconds (Date.now() on server)
 * @property t2ServerTx Server transmit time in epoch milliseconds (Date.now() on server)
 * @property t3ClientElapsed Monotonic client receive time (SystemClock.elapsedRealtime)
 * @property rttMs Round-trip time in milliseconds: (t3 - t0) - (t2 - t1)
 * @property rawTheta Clock offset relative to elapsedRealtime: estimatedServerTime - elapsedRealtime
 */
data class SyncSample(
    val t0ClientElapsed: Long,
    val t1ServerRx: Long,
    val t2ServerTx: Long,
    val t3ClientElapsed: Long,
    val rttMs: Long,
    val rawTheta: Long
)

/**
 * Live state of clock synchronization observed by debug overlay and audio engine.
 *
 * @property theta Monotonic clock offset such that ServerTime = SystemClock.elapsedRealtime() + theta
 * @property latestRttMs RTT of the most recent sync exchange in milliseconds
 * @property smoothedRttMs Exponential moving average or median of recent RTT measurements
 * @property minRttMs Lowest observed RTT
 * @property sampleCount Total number of successful samples received
 * @property lastSyncElapsed Monotonic timestamp when the last sample was recorded
 * @property isSynchronized True when minimum required samples have been processed
 */
data class ClockSyncState(
    val theta: Long = 0L,
    val latestRttMs: Long = 0L,
    val smoothedRttMs: Long = 0L,
    val minRttMs: Long = Long.MAX_VALUE,
    val sampleCount: Int = 0,
    val lastSyncElapsed: Long = 0L,
    val isSynchronized: Boolean = false
)

/**
 * Cristian's Algorithm Clock Synchronizer with Median + EMA Jitter Filtering.
 *
 * Calculates: ServerTime = SystemClock.elapsedRealtime() + theta (θ)
 *
 * @param sendPing Lambda invoked to dispatch SYNC_PING message with clientTx.
 * @param burstCount Number of pings per sync burst to minimize network jitter.
 * @param pingIntervalMs Delay between consecutive pings within a burst.
 * @param syncPeriodMs Interval between automatic background sync bursts.
 * @param pingTimeoutMs Timeout to wait for an individual SYNC_PONG.
 * @param windowSize Number of historical samples retained for median calculation (5–10).
 * @param emaAlpha Weight factor (0.0 < alpha <= 1.0) for Exponential Moving Average smoothing.
 * @param monotonicTimeProvider Supplier for monotonic clock (defaults to SystemClock.elapsedRealtime).
 */
class ClockSynchronizer(
    private val sendPing: (clientTx: Long) -> Boolean,
    private val burstCount: Int = 5,
    private val pingIntervalMs: Long = 200L,
    private val syncPeriodMs: Long = 30_000L,
    private val pingTimeoutMs: Long = 1_500L,
    private val windowSize: Int = 8,
    private val emaAlpha: Double = 0.25,
    private val monotonicTimeProvider: () -> Long = { SystemClock.elapsedRealtime() }
) {
    private val _syncState = MutableStateFlow(ClockSyncState())
    val syncState: StateFlow<ClockSyncState> = _syncState.asStateFlow()

    // Map clientTx identifier -> CompletableDeferred<Pair<serverRx, serverTx>>
    private val pendingPings = ConcurrentHashMap<Long, CompletableDeferred<Pair<Long, Long>>>()
    private val sampleHistory = ArrayDeque<SyncSample>(windowSize)
    private val syncMutex = Mutex()
    private var syncJob: Job? = null

    /**
     * Starts the periodic synchronization loop in the provided CoroutineScope.
     */
    fun start(scope: CoroutineScope) {
        stop()
        syncJob = scope.launch {
            while (isActive) {
                try {
                    syncNow()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(syncPeriodMs)
            }
        }
    }

    /**
     * Stops the background synchronization loop and cleans up pending requests.
     */
    fun stop() {
        syncJob?.cancel()
        syncJob = null
        pendingPings.values.forEach { it.cancel() }
        pendingPings.clear()
    }

    /**
     * Executes a burst of NTP-style pings, filters jitter via sliding-window median + EMA,
     * and updates the synchronized clock offset θ.
     *
     * @return The best SyncSample from this burst, or null if all pings timed out.
     */
    suspend fun syncNow(): SyncSample? = syncMutex.withLock {
        val burstSamples = mutableListOf<SyncSample>()

        for (i in 0 until burstCount) {
            val t0 = monotonicTimeProvider()
            val deferred = CompletableDeferred<Pair<Long, Long>>()
            pendingPings[t0] = deferred

            val sent = sendPing(t0)
            if (!sent) {
                pendingPings.remove(t0)
                continue
            }

            val result = withTimeoutOrNull(pingTimeoutMs) {
                deferred.await()
            }
            pendingPings.remove(t0)

            if (result != null) {
                val t3 = monotonicTimeProvider()
                val (t1, t2) = result

                val serverProcessingTime = (t2 - t1).coerceAtLeast(0L)
                val rtt = ((t3 - t0) - serverProcessingTime).coerceAtLeast(0L)

                // Cristian's algorithm:
                // Midpoint of client request is t0 + (rtt / 2)
                // Server time at that moment is approximately t1 (or (t1 + t2)/2)
                // Offset θ relative to client elapsedRealtime:
                // ServerTime(now) = ServerTime(midpoint) + (t3 - midpoint)
                //                = t2 + (t3 - t0 - serverProcessing)/2
                val estimatedServerNow = t2 + (rtt / 2L)
                val rawTheta = estimatedServerNow - t3

                burstSamples.add(
                    SyncSample(
                        t0ClientElapsed = t0,
                        t1ServerRx = t1,
                        t2ServerTx = t2,
                        t3ClientElapsed = t3,
                        rttMs = rtt,
                        rawTheta = rawTheta
                    )
                )
            }

            if (i < burstCount - 1) {
                delay(pingIntervalMs)
            }
        }

        if (burstSamples.isEmpty()) return null

        // In the current burst, pick the lowest RTT sample (least asymmetric delay)
        val bestBurstSample = burstSamples.minByOrNull { it.rttMs } ?: return null

        // Add to sliding window history
        if (sampleHistory.size >= windowSize) {
            sampleHistory.removeFirst()
        }
        sampleHistory.addLast(bestBurstSample)

        // 1. Median filtering across sliding window to discard outliers
        val sortedThetas = sampleHistory.map { it.rawTheta }.sorted()
        val medianTheta = sortedThetas[sortedThetas.size / 2]

        val sortedRtts = sampleHistory.map { it.rttMs }.sorted()
        val medianRtt = sortedRtts[sortedRtts.size / 2]

        // 2. Exponential Moving Average (EMA) to smoothly update θ without phase jumps
        val currentTheta = _syncState.value.theta
        val smoothedTheta = if (!_syncState.value.isSynchronized) {
            medianTheta // Initial sync: seed directly with median
        } else {
            (emaAlpha * medianTheta + (1.0 - emaAlpha) * currentTheta).toLong()
        }

        val currentSmoothedRtt = _syncState.value.smoothedRttMs
        val smoothedRtt = if (!_syncState.value.isSynchronized) {
            medianRtt
        } else {
            (emaAlpha * medianRtt + (1.0 - emaAlpha) * currentSmoothedRtt).toLong()
        }

        _syncState.value = ClockSyncState(
            theta = smoothedTheta,
            latestRttMs = bestBurstSample.rttMs,
            smoothedRttMs = smoothedRtt,
            minRttMs = minOf(_syncState.value.minRttMs, bestBurstSample.rttMs),
            sampleCount = _syncState.value.sampleCount + 1,
            lastSyncElapsed = monotonicTimeProvider(),
            isSynchronized = true
        )

        return bestBurstSample
    }

    /**
     * Must be called whenever a `SYNC_PONG` message arrives from the WebSocket.
     *
     * @param clientTx Monotonic timestamp originally sent in SYNC_PING (T0).
     * @param serverRx Server receive timestamp in epoch ms (T1).
     * @param serverTx Server transmit timestamp in epoch ms (T2).
     */
    fun onSyncPongReceived(clientTx: Long, serverRx: Long, serverTx: Long) {
        val deferred = pendingPings[clientTx]
        deferred?.complete(Pair(serverRx, serverTx))
    }

    /**
     * Calculates the estimated server time in milliseconds:
     * ServerTime = SystemClock.elapsedRealtime() + θ
     */
    fun currentServerTimeMillis(): Long {
        return monotonicTimeProvider() + _syncState.value.theta
    }

    /**
     * Converts a local monotonic timestamp (elapsedRealtime) to estimated server epoch time.
     */
    fun toServerTime(clientElapsedRealtimeMs: Long): Long {
        return clientElapsedRealtimeMs + _syncState.value.theta
    }

    /**
     * Converts an estimated server epoch timestamp back to local monotonic time (elapsedRealtime).
     */
    fun toElapsedRealtime(serverEpochMs: Long): Long {
        return serverEpochMs - _syncState.value.theta
    }

    /**
     * Resets synchronizer history and state.
     */
    fun reset() {
        stop()
        sampleHistory.clear()
        _syncState.value = ClockSyncState()
    }
}
