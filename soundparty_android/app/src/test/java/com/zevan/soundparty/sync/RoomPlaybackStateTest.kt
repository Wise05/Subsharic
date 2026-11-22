package com.zevan.soundparty.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import com.zevan.soundparty.settings.BluetoothLatencyPreferences

class RoomPlaybackStateTest {
    @Test
    fun playingStateAdvancesFromItsServerTimestamp() {
        val state = RoomPlaybackState(
            track = RoomTrack("track-1", "Track"),
            isPlaying = true,
            positionMs = 12_000L,
            updatedAt = 1_000_000L
        )

        assertEquals(12_750L, state.positionAt(1_000_750L))
    }

    @Test
    fun pausedStateKeepsItsSeekOffset() {
        val state = RoomPlaybackState(
            track = RoomTrack("track-1", "Track"),
            isPlaying = false,
            positionMs = 12_000L,
            updatedAt = 1_000_000L
        )

        assertEquals(12_000L, state.positionAt(1_000_750L))
    }

    @Test
    fun futureAnchorDoesNotMoveThePlayheadBackwards() {
        val state = RoomPlaybackState(
            track = null,
            isPlaying = true,
            positionMs = 12_000L,
            updatedAt = 1_000_750L
        )

        assertEquals(12_000L, state.positionAt(1_000_000L))
    }

    @Test
    fun queueReorderingUsesStableEntryIdsAndIgnoresInvalidIndices() {
        val first = RoomQueueEntry("first", RoomTrack("a", "A"))
        val second = RoomQueueEntry("second", RoomTrack("b", "B"))
        assertEquals(listOf(second, first), reorderQueue(listOf(first, second), 0, 1))
        assertEquals(listOf(first, second), reorderQueue(listOf(first, second), -1, 1))
    }

    @Test
    fun bluetoothLatencyOffsetIsBoundedAndAdvancesPositiveTargetsEarlier() {
        assertEquals(-200, BluetoothLatencyPreferences.normalizeOffsetMs(-900))
        assertEquals(200, BluetoothLatencyPreferences.normalizeOffsetMs(900))
        assertEquals(0, BluetoothLatencyPreferences.normalizeOffsetMs(0))
        assertEquals(
            1_500L,
            PlaybackTiming.localExecutionElapsedMs(
                actionTimeServerMs = 1_500L,
                thetaMs = 100L,
                leadTimeMs = 400L,
                bluetoothLatencyOffsetMs = 300L
            )
        )
    }
}
