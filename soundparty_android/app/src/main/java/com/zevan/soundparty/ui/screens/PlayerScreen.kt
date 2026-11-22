package com.zevan.soundparty.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.session.MediaController
import com.zevan.soundparty.sync.RoomQueueEntry
import com.zevan.soundparty.sync.MusicAlbum
import com.zevan.soundparty.sync.RoomSessionState
import com.zevan.soundparty.sync.RoomTrack
import com.zevan.soundparty.sync.SyncBrokerClient
import com.zevan.soundparty.sync.WebSocketConnectionState
import com.zevan.soundparty.sync.reorderQueue
import com.zevan.soundparty.ui.components.BluetoothLatencyControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.Locale

@Composable
fun PlayerScreen(
    room: RoomSessionState,
    serverError: String?,
    playbackPreparation: String?,
    controller: MediaController?,
    syncBrokerClient: SyncBrokerClient,
    connectionState: WebSocketConnectionState,
    bluetoothOffsetMs: Int,
    onBluetoothOffsetChange: (Int) -> Unit,
    onLeaveRoom: () -> Unit,
    onTogglePlayback: (Boolean) -> Unit,
    onSeek: (Long) -> Unit,
    onPlayTrack: (RoomTrack) -> Unit,
    onSearchTracks: suspend (String) -> List<RoomTrack>,
    onListAlbums: suspend (Int) -> List<MusicAlbum>,
    onGetAlbumTracks: suspend (String) -> List<RoomTrack>,
    onAddTrack: (RoomTrack) -> Boolean,
    onRemoveQueueEntry: (String) -> Boolean,
    onReorderQueue: (List<String>) -> Boolean
) {
    val playerStatus by produceState(initialValue = "Player unavailable", key1 = controller) {
        val activeController = controller
        if (activeController == null) {
            value = "Player unavailable"
            return@produceState
        }
        fun currentStatus(): String = when (activeController.playbackState) {
            Player.STATE_IDLE -> "Idle"
            Player.STATE_BUFFERING -> "Buffering"
            Player.STATE_READY -> if (activeController.isPlaying) "Playing" else "Ready, paused"
            Player.STATE_ENDED -> "Track ended"
            else -> "Unknown player state"
        }
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                value = currentStatus()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                value = currentStatus()
            }

            override fun onPlayerError(error: PlaybackException) {
                value = "Playback error: ${error.errorCodeName} — ${error.message ?: "no details"}"
            }
        }
        activeController.addListener(listener)
        value = currentStatus()
        awaitDispose { activeController.removeListener(listener) }
    }
    val playback = room.playback
    val currentTrack = playback?.track
    var positionMs by remember(currentTrack?.id, playback?.positionMs) {
        mutableLongStateOf(playback?.positionMs ?: 0L)
    }
    var seekPreviewMs by remember { mutableLongStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<RoomTrack>>(emptyList()) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    var browseAlbums by remember { mutableStateOf(false) }
    var albums by remember { mutableStateOf<List<MusicAlbum>>(emptyList()) }
    var albumOffset by remember { mutableStateOf(0) }
    var hasMoreAlbums by remember { mutableStateOf(true) }
    var isLoadingAlbums by remember { mutableStateOf(false) }
    var selectedAlbum by remember { mutableStateOf<MusicAlbum?>(null) }
    var albumTracks by remember { mutableStateOf<List<RoomTrack>>(emptyList()) }
    var albumError by remember { mutableStateOf<String?>(null) }
    var isLoadingAlbumTracks by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun loadMoreAlbums() {
        if (isLoadingAlbums || !hasMoreAlbums) return
        scope.launch {
            isLoadingAlbums = true
            albumError = null
            runCatching { onListAlbums(albumOffset) }
                .onSuccess { page ->
                    albums = albums + page
                    albumOffset += page.size
                    hasMoreAlbums = page.size == 50
                }
                .onFailure { albumError = it.message ?: "Could not load albums" }
            isLoadingAlbums = false
        }
    }

    LaunchedEffect(controller, currentTrack?.id) {
        while (true) {
            if (!isSeeking && controller != null && controller.playbackState != Player.STATE_IDLE) {
                positionMs = controller.currentPosition.coerceAtLeast(0L)
            }
            delay(250L)
        }
    }

    val durationMs = currentTrack?.durationMs?.takeIf { it > 0L }
        ?: controller?.duration?.takeIf { it > 0L }
        ?: 0L
    val displayedPosition = if (isSeeking) seekPreviewMs else positionMs
    val displayedIsPlaying = if (room.isHost && controller != null) {
        controller.isPlaying
    } else {
        playback?.isPlaying == true
    }
    val artworkUrl = syncBrokerClient.resolveArtworkUrl(currentTrack?.artworkUrl)

    fun addTrack(track: RoomTrack): Boolean = onAddTrack(track)

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("player-screen-list"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    SelectionContainer {
                        Text("Room ${room.roomId}", style = MaterialTheme.typography.titleLarge)
                    }
                    Text(if (room.isHost) "You’re the host" else "Listening with the host")
                }
                OutlinedButton(onClick = onLeaveRoom) { Text("Leave") }
            }
        }

        serverError?.let { error ->
            item { Text(error, color = MaterialTheme.colorScheme.error) }
        }
        item {
            Text(
                when (connectionState) {
                    WebSocketConnectionState.CONNECTED -> "Broker connected"
                    WebSocketConnectionState.CONNECTING -> "Connecting to broker…"
                    WebSocketConnectionState.DISCONNECTED -> "Broker disconnected"
                    WebSocketConnectionState.ERROR -> "Broker connection error"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (connectionState == WebSocketConnectionState.CONNECTED) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
        }

        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ArtworkImage(
                        artworkUrl = artworkUrl,
                        modifier = Modifier.size(190.dp).clip(RoundedCornerShape(18.dp))
                    )
                    Spacer(Modifier.height(14.dp))
                    Text("Now Playing", style = MaterialTheme.typography.titleMedium)
                    Text(playerStatus, style = MaterialTheme.typography.bodySmall)
                    playbackPreparation?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text(currentTrack?.title ?: "Nothing playing", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        listOfNotNull(currentTrack?.artist, currentTrack?.album).joinToString(" · ")
                            .ifBlank { "Choose a track from the queue" },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = displayedPosition.coerceAtMost(durationMs).toFloat(),
                        onValueChange = {
                            isSeeking = true
                            seekPreviewMs = it.toLong()
                        },
                        onValueChangeFinished = {
                            if (room.isHost) onSeek(seekPreviewMs.coerceIn(0L, durationMs))
                            isSeeking = false
                            positionMs = seekPreviewMs.coerceIn(0L, durationMs)
                        },
                        valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
                        enabled = room.isHost && durationMs > 0L,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatTime(displayedPosition))
                        Text(formatTime(durationMs))
                    }
                    Button(
                        onClick = { onTogglePlayback(!displayedIsPlaying) },
                        enabled = room.isHost && currentTrack != null,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text(if (displayedIsPlaying) "Pause" else "Play")
                    }
                    if (!room.isHost) {
                        Text("Playback is controlled by the room host.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item {
            BluetoothLatencyControl(
                offsetMs = bluetoothOffsetMs,
                onOffsetChange = onBluetoothOffsetChange,
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            Text("Add music", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (browseAlbums) {
                    OutlinedButton(onClick = {
                        browseAlbums = false
                        selectedAlbum = null
                    }) { Text("Tracks") }
                    Button(onClick = { if (albums.isEmpty()) loadMoreAlbums() }) { Text("Albums") }
                } else {
                    Button(onClick = { browseAlbums = false }) { Text("Tracks") }
                    OutlinedButton(onClick = {
                        browseAlbums = true
                        selectedAlbum = null
                        if (albums.isEmpty()) loadMoreAlbums()
                    }) { Text("Albums") }
                }
            }
        }

        if (!browseAlbums) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it; searchError = null },
                        label = { Text("Search tracks") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.size(8.dp))
                    Button(
                        enabled = query.isNotBlank() && !isSearching,
                        onClick = {
                            scope.launch {
                                isSearching = true
                                searchError = null
                                runCatching { onSearchTracks(query.trim()) }
                                    .onSuccess { results = it }
                                    .onFailure { searchError = it.message ?: "Search failed" }
                                isSearching = false
                            }
                        }
                    ) { Text(if (isSearching) "…" else "Search") }
                }
                searchError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            items(results, key = { "search-${it.id}-${it.title}" }) { track ->
                TrackRow(track = track, actionLabel = "Add", onAction = { addTrack(track) })
            }
        } else if (selectedAlbum == null) {
            item {
                Text("Albums", style = MaterialTheme.typography.titleMedium)
                albumError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            items(albums, key = { "album-${it.id}" }) { album ->
                AlbumRow(album, syncBrokerClient.resolveArtworkUrl(album.artworkUrl)) {
                    selectedAlbum = album
                    albumTracks = emptyList()
                    albumError = null
                    scope.launch {
                        isLoadingAlbumTracks = true
                        runCatching { onGetAlbumTracks(album.id) }
                            .onSuccess { albumTracks = it }
                            .onFailure { albumError = it.message ?: "Could not load album tracks" }
                        isLoadingAlbumTracks = false
                    }
                }
            }
            if (hasMoreAlbums) item {
                OutlinedButton(onClick = ::loadMoreAlbums, enabled = !isLoadingAlbums) {
                    Text(if (isLoadingAlbums) "Loading…" else "Load more albums")
                }
            }
            if (albums.isEmpty() && !isLoadingAlbums && albumError == null) {
                item { Text("No albums found.") }
            }
        } else {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        selectedAlbum = null
                        albumError = null
                    }) { Text("Back") }
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(selectedAlbum?.title.orEmpty(), style = MaterialTheme.typography.titleMedium)
                        Text(selectedAlbum?.artist.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    }
                }
                albumError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (isLoadingAlbumTracks) Text("Loading tracks…")
                if (!isLoadingAlbumTracks && albumTracks.isEmpty() && albumError == null) {
                    Text("No tracks in this album.")
                }
            }
            items(albumTracks, key = { "album-track-${selectedAlbum?.id}-${it.id}" }) { track ->
                TrackRow(track = track, actionLabel = "Add", onAction = { addTrack(track) })
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Queue", style = MaterialTheme.typography.titleLarge)
                Text("${room.queue.size} tracks", style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (room.queue.isEmpty()) item { Text("Your shared queue is empty. Search and add a track.") }

        items(room.queue, key = { it.queueId }) { entry ->
            val index = room.queue.indexOfFirst { it.queueId == entry.queueId }
            QueueRow(
                entry = entry,
                hostControls = room.isHost,
                onPlay = { onPlayTrack(entry.track) },
                onMoveUp = {
                    onReorderQueue(reorderQueue(room.queue, index, index - 1).map { it.queueId })
                },
                onMoveDown = {
                    onReorderQueue(reorderQueue(room.queue, index, index + 1).map { it.queueId })
                },
                onRemove = { onRemoveQueueEntry(entry.queueId) }
            )
        }
    }
}

@Composable
private fun TrackRow(track: RoomTrack, actionLabel: String, onAction: () -> Boolean) {
    var actionFeedback by remember(track.id) { mutableStateOf(actionLabel) }
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(track.artist, track.album).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            OutlinedButton(onClick = {
                actionFeedback = if (onAction()) "Sent" else "Retry"
            }) { Text(actionFeedback) }
        }
    }
}

@Composable
private fun AlbumRow(album: MusicAlbum, artworkUrl: String?, onOpen: () -> Unit) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ArtworkImage(
                artworkUrl = artworkUrl,
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))
            )
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(album.artist, album.songCount?.let { "$it tracks" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text("View")
        }
    }
}

@Composable
private fun QueueRow(
    entry: RoomQueueEntry,
    hostControls: Boolean,
    onPlay: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(entry.track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(entry.track.artist.orEmpty(), style = MaterialTheme.typography.bodySmall)
            }
            if (hostControls) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(onClick = onPlay) { Text("Play") }
                        OutlinedButton(onClick = onMoveUp) { Text("Up") }
                        OutlinedButton(onClick = onMoveDown) { Text("Down") }
                    }
                    OutlinedButton(onClick = onRemove) { Text("Remove") }
                }
            } else {
                Text("Queued", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ArtworkImage(artworkUrl: String?, modifier: Modifier = Modifier) {
    val image by produceState<ImageBitmap?>(initialValue = null, key1 = artworkUrl) {
        value = null
        if (artworkUrl != null) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    URL(artworkUrl).openConnection().apply {
                        connectTimeout = 5_000
                        readTimeout = 5_000
                    }.getInputStream().use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    if (image != null) {
        Image(image!!, contentDescription = "Album artwork", modifier = modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Text("♫", style = MaterialTheme.typography.displayLarge, color = Color.Gray)
        }
    }
}

private fun formatTime(positionMs: Long): String {
    val totalSeconds = (positionMs / 1_000L).coerceAtLeast(0L)
    return String.format(Locale.getDefault(), "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}
