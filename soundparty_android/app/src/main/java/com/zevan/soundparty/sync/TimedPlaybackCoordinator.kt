package com.zevan.soundparty.sync

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A room PLAY action stamped in the broker's epoch-millisecond clock. */
data class PlayAction(
    val actionTimeServerMs: Long,
    val positionMs: Long,
    val mediaUri: String
)

object PlaybackTiming {
    fun localExecutionElapsedMs(
        actionTimeServerMs: Long,
        thetaMs: Long,
        leadTimeMs: Long = 400L,
        bluetoothLatencyOffsetMs: Long = 0L
    ): Long = actionTimeServerMs + leadTimeMs - thetaMs - bluetoothLatencyOffsetMs
}

/** Schedules room playback on the local monotonic clock and keeps it phase aligned. */
class TimedPlaybackCoordinator(
    private val player: Player,
    private val clockSynchronizer: ClockSynchronizer,
    private val scope: CoroutineScope,
    private val leadTimeMs: Long = 400L,
    private val driftCalculator: DriftCalculator = DriftCalculator(),
    private val monotonicTimeProvider: () -> Long = { SystemClock.elapsedRealtime() }
) {
    private var scheduledPlay: Job? = null
    private var driftMonitor: Job? = null

    /** T_start = T_action + 400ms; local execution deadline = T_start - theta. */
    fun schedulePlay(action: PlayAction, bluetoothLatencyOffsetMs: Long = 0L) {
        if (!clockSynchronizer.syncState.value.isSynchronized) return
        scheduledPlay?.cancel()
        // Positive latency means Bluetooth output is late, so start local playback early.
        val localExecutionMs = PlaybackTiming.localExecutionElapsedMs(
            actionTimeServerMs = action.actionTimeServerMs,
            thetaMs = clockSynchronizer.syncState.value.theta,
            leadTimeMs = leadTimeMs,
            bluetoothLatencyOffsetMs = bluetoothLatencyOffsetMs
        )
        val targetExecutionMs = clockSynchronizer.toElapsedRealtime(action.actionTimeServerMs + leadTimeMs)
        scheduledPlay = scope.launch {
            val remainingMs = localExecutionMs - monotonicTimeProvider()
            if (remainingMs > 0L) delay(remainingMs)

            // A late delivery catches up to the shared timeline, not to the offset-adjusted deadline.
            val lateByMs = (monotonicTimeProvider() - targetExecutionMs).coerceAtLeast(0L)
            player.setMediaItem(MediaItem.fromUri(action.mediaUri), action.positionMs + lateByMs)
            player.prepare()
            player.play()
            startDriftMonitoring(action.positionMs, localExecutionMs)
        }
    }

    /** Execute a paused snapshot at the same room action boundary, retaining the playhead. */
    fun schedulePause(actionTimeServerMs: Long, positionMs: Long, mediaUri: String) {
        if (!clockSynchronizer.syncState.value.isSynchronized) return
        scheduledPlay?.cancel()
        driftMonitor?.cancel()
        val localExecutionMs = PlaybackTiming.localExecutionElapsedMs(
            actionTimeServerMs = actionTimeServerMs,
            thetaMs = clockSynchronizer.syncState.value.theta,
            leadTimeMs = leadTimeMs
        )
        scheduledPlay = scope.launch {
            val remainingMs = localExecutionMs - monotonicTimeProvider()
            if (remainingMs > 0L) delay(remainingMs)
            player.setMediaItem(MediaItem.fromUri(mediaUri), positionMs)
            player.prepare()
            player.pause()
            player.setPlaybackParameters(PlaybackParameters(1f))
        }
    }

    fun cancel() {
        scheduledPlay?.cancel()
        scheduledPlay = null
        driftMonitor?.cancel()
        driftMonitor = null
        player.setPlaybackParameters(PlaybackParameters(1f))
    }

    private fun startDriftMonitoring(startPositionMs: Long, startElapsedMs: Long) {
        driftMonitor?.cancel()
        driftMonitor = scope.launch {
            while (isActive && player.playbackState != Player.STATE_ENDED) {
                val elapsedSinceStart = (monotonicTimeProvider() - startElapsedMs).coerceAtLeast(0L)
                val targetPositionMs = startPositionMs + elapsedSinceStart
                if (player.isPlaying) driftCalculator.apply(player, targetPositionMs)
                delay(500L)
            }
            player.setPlaybackParameters(PlaybackParameters(1f))
        }
    }
}
