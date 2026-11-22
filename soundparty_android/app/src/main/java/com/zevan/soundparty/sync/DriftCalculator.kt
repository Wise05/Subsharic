package com.zevan.soundparty.sync

import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import kotlin.math.abs

/** Correction to apply when comparing the local playhead with the room timeline. */
sealed interface DriftCorrection {
    data class Seek(val positionMs: Long) : DriftCorrection
    data class AdjustSpeed(val speed: Float) : DriftCorrection
    data object InTolerance : DriftCorrection
}

/**
 * Turns playhead error into a hard seek or a small playback-rate correction.
 * Positive drift means the local player is ahead of the room timeline.
 */
class DriftCalculator(
    // Small timestamp jitter is normal during streamed playback. Seeking at 150ms repeatedly
    // forces the media source to refill its buffers, so reserve seeks for visible desync.
    private val hardSeekThresholdMs: Long = 1_500L,
    private val speedAdjustmentThresholdMs: Long = 15L
) {
    fun calculate(localPositionMs: Long, targetPositionMs: Long): DriftCorrection {
        val driftMs = localPositionMs - targetPositionMs
        val magnitude = abs(driftMs)
        return when {
            magnitude > hardSeekThresholdMs -> DriftCorrection.Seek(targetPositionMs)
            magnitude > speedAdjustmentThresholdMs -> {
                // Faster when behind, slower when ahead. Scale gently and clamp to ±1%.
                val adjustment = (driftMs.toDouble() / hardSeekThresholdMs)
                    .coerceIn(-1.0, 1.0) * 0.01
                DriftCorrection.AdjustSpeed((1.0 - adjustment).toFloat())
            }
            else -> DriftCorrection.InTolerance
        }
    }

    fun apply(player: Player, targetPositionMs: Long): DriftCorrection {
        val correction = calculate(player.currentPosition, targetPositionMs)
        when (correction) {
            is DriftCorrection.Seek -> {
                player.seekTo(correction.positionMs)
                player.setPlaybackParameters(PlaybackParameters(1f))
            }
            is DriftCorrection.AdjustSpeed ->
                player.setPlaybackParameters(PlaybackParameters(correction.speed))
            DriftCorrection.InTolerance ->
                player.setPlaybackParameters(PlaybackParameters(1f))
        }
        return correction
    }
}
