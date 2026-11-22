package com.zevan.soundparty.sync

/** Track identity and display title shared by room members. */
data class RoomTrack(
    val id: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    val artworkUrl: String? = null
)

data class MusicAlbum(
    val id: String,
    val title: String,
    val artist: String? = null,
    val artworkUrl: String? = null,
    val songCount: Int? = null
)

data class RoomQueueEntry(val queueId: String, val track: RoomTrack)

/** A playhead position anchored to broker epoch time. */
data class RoomPlaybackState(
    val track: RoomTrack?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val updatedAt: Long
) {
    /** Project the anchored position to the estimated current broker time. */
    fun positionAt(serverNowMs: Long): Long =
        if (isPlaying) positionMs + (serverNowMs - updatedAt).coerceAtLeast(0L) else positionMs
}

data class RoomSessionState(
    val roomId: String,
    val hostId: String,
    val clientId: String,
    val playback: RoomPlaybackState? = null,
    val queue: List<RoomQueueEntry> = emptyList()
) {
    val isHost: Boolean get() = hostId == clientId
}

fun reorderQueue(queue: List<RoomQueueEntry>, fromIndex: Int, toIndex: Int): List<RoomQueueEntry> {
    if (fromIndex !in queue.indices || toIndex !in queue.indices || fromIndex == toIndex) return queue
    return queue.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
}
