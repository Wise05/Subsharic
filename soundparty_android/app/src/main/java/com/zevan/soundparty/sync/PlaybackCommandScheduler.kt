package com.zevan.soundparty.sync

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController

/**
 * Schedules incoming PLAY/PAUSE commands to execute at a precise local monotonic time.
 *
 * Protocol:
 *   - Server broadcasts STATE_UPDATED with `targetStartTime` (server epoch ms) = T_action + 400ms
 *   - Scheduler converts: t_local = targetStartTime − θ  (elapsedRealtime-based)
 *   - Uses Handler.postAtTime(r, t_local) for sub-millisecond scheduling accuracy
 *
 * @param clockSynchronizer Provides θ via [ClockSynchronizer.toElapsedRealtime]
 * @param getController Lambda returning the current [MediaController], may be null
 */
class PlaybackCommandScheduler(
    private val clockSynchronizer: ClockSynchronizer,
    private val getController: () -> MediaController?
) {
    private val handler = Handler(Looper.getMainLooper())
    private var pendingToken: Any? = null

    /**
     * Schedule a PLAY command.
     *
     * @param streamUrl     The media URL to load (may be null if already loaded)
     * @param positionMs    Playhead position to seek to before playing
     * @param targetStartTime Server epoch timestamp (ms) at which all clients should start
     */
    fun schedulePlay(streamUrl: String?, positionMs: Long, targetStartTime: Long) {
        cancel()

        // Convert server epoch target → local monotonic time
        val tLocal = clockSynchronizer.toElapsedRealtime(targetStartTime)
        val nowElapsed = SystemClock.elapsedRealtime()
        val delayMs = tLocal - nowElapsed

        Log.d(TAG, "schedulePlay: targetStartTime=$targetStartTime tLocal=$tLocal nowElapsed=$nowElapsed delayMs=${delayMs}ms positionMs=$positionMs")

        if (delayMs < -MAX_LATE_MS) {
            // Command is too stale to be useful; execute immediately at correct position
            Log.w(TAG, "schedulePlay: command ${-delayMs}ms late, executing immediately")
            executePlay(streamUrl, positionMs)
            return
        }

        val token = Object()
        pendingToken = token

        val runnable = Runnable {
            if (pendingToken === token) {
                pendingToken = null
                executePlay(streamUrl, positionMs)
            }
        }

        if (delayMs <= 0) {
            // Target is now or very slightly in the past — post immediately
            handler.post(runnable)
        } else {
            handler.postAtTime(runnable, token, tLocal)
        }
    }

    /**
     * Schedule a PAUSE command.
     *
     * @param targetStartTime Server epoch timestamp (ms) at which all clients should pause
     */
    fun schedulePause(targetStartTime: Long) {
        cancel()

        val tLocal = clockSynchronizer.toElapsedRealtime(targetStartTime)
        val nowElapsed = SystemClock.elapsedRealtime()
        val delayMs = tLocal - nowElapsed

        Log.d(TAG, "schedulePause: delayMs=${delayMs}ms")

        val token = Object()
        pendingToken = token

        val runnable = Runnable {
            if (pendingToken === token) {
                pendingToken = null
                executePause()
            }
        }

        if (delayMs <= 0) {
            handler.post(runnable)
        } else {
            handler.postAtTime(runnable, token, tLocal)
        }
    }

    /** Cancel any pending scheduled command. */
    fun cancel() {
        val token = pendingToken
        if (token != null) {
            handler.removeCallbacksAndMessages(token)
            pendingToken = null
        }
    }

    private fun executePlay(streamUrl: String?, positionMs: Long) {
        val controller = getController() ?: run {
            Log.w(TAG, "executePlay: MediaController not available")
            return
        }

        if (streamUrl != null) {
            controller.setMediaItem(MediaItem.fromUri(streamUrl))
            controller.prepare()
        }

        if (positionMs > 0) {
            controller.seekTo(positionMs)
        }

        controller.play()
        Log.d(TAG, "executePlay: ExoPlayer started at positionMs=$positionMs")
    }

    private fun executePause() {
        val controller = getController() ?: run {
            Log.w(TAG, "executePause: MediaController not available")
            return
        }
        controller.pause()
        Log.d(TAG, "executePause: ExoPlayer paused")
    }

    companion object {
        private const val TAG = "PlaybackCmdScheduler"
        /** If the target time is more than this many ms in the past, skip scheduling. */
        private const val MAX_LATE_MS = 2_000L
    }
}
