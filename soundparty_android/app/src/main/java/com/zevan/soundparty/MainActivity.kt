package com.zevan.soundparty

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.zevan.soundparty.service.MediaPlaybackService
import com.zevan.soundparty.settings.BluetoothLatencyPreferences
import com.zevan.soundparty.sync.PlayAction
import com.zevan.soundparty.sync.RoomPlaybackState
import com.zevan.soundparty.sync.SyncBrokerClient
import com.zevan.soundparty.sync.TimedPlaybackCoordinator
import com.zevan.soundparty.ui.screens.LobbyScreen
import com.zevan.soundparty.ui.screens.PlayerScreen
import com.zevan.soundparty.ui.theme.SoundPartyTheme

class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController by mutableStateOf<MediaController?>(null)
    private val syncBrokerClient by lazy {
        SyncBrokerClient(BuildConfig.SYNC_BROKER_URL)
    }
    private val latencyPreferences by lazy { BluetoothLatencyPreferences(this) }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, MediaPlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            if (controllerFuture !== future) return@addListener
            mediaController = runCatching { future.get() }.getOrNull()
        }, MoreExecutors.directExecutor())
        syncBrokerClient.connect()
    }

    override fun onStop() {
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        mediaController = null
        syncBrokerClient.disconnect()
        super.onStop()
    }

    override fun onDestroy() {
        syncBrokerClient.destroy()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SoundPartyTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val connectionState by syncBrokerClient.connectionState.collectAsState()
                    val roomState by syncBrokerClient.roomState.collectAsState()
                    val serverError by syncBrokerClient.serverError.collectAsState()
                    val playbackPreparation by syncBrokerClient.playbackPreparation.collectAsState()
                    val clockState by syncBrokerClient.clockSynchronizer.syncState.collectAsState()
                    val bluetoothOffset by latencyPreferences.offsetMs.collectAsState()
                    val latestBluetoothOffset by rememberUpdatedState(bluetoothOffset)
                    val scope = rememberCoroutineScope()
                    val coordinator = remember(mediaController) {
                        mediaController?.let { TimedPlaybackCoordinator(it, syncBrokerClient.clockSynchronizer, scope) }
                    }

                    LaunchedEffect(coordinator) {
                        val active = coordinator ?: return@LaunchedEffect
                        syncBrokerClient.playActions.collect { action ->
                            active.schedulePlay(action, latestBluetoothOffset.toLong())
                        }
                    }

                    LaunchedEffect(coordinator, roomState?.playback, clockState.isSynchronized) {
                        val active = coordinator ?: return@LaunchedEffect
                        if (!clockState.isSynchronized) return@LaunchedEffect
                        val playback = roomState?.playback ?: return@LaunchedEffect
                        val track = playback.track ?: return@LaunchedEffect
                        val streamUrl = syncBrokerClient.streamUrlForTrack(track.id)
                        if (playback.isPlaying) {
                            active.schedulePlay(
                                PlayAction(playback.updatedAt, playback.positionMs, streamUrl),
                                latestBluetoothOffset.toLong()
                            )
                        } else {
                            active.schedulePause(playback.updatedAt, playback.positionMs, streamUrl)
                        }
                    }

                    DisposableEffect(coordinator) {
                        onDispose { coordinator?.cancel() }
                    }

                    if (roomState == null) {
                        LobbyScreen(
                            connectionState = connectionState,
                            serverError = serverError,
                            onCreateRoom = { syncBrokerClient.createRoom() },
                            onJoinRoom = syncBrokerClient::joinRoom,
                            bluetoothOffsetMs = bluetoothOffset,
                            onBluetoothOffsetChange = latencyPreferences::setOffsetMs
                        )
                    } else {
                        val room = requireNotNull(roomState)
                        PlayerScreen(
                            room = room,
                            controller = mediaController,
                            syncBrokerClient = syncBrokerClient,
                            connectionState = connectionState,
                            serverError = serverError,
                            playbackPreparation = playbackPreparation,
                            bluetoothOffsetMs = bluetoothOffset,
                            onBluetoothOffsetChange = latencyPreferences::setOffsetMs,
                            onLeaveRoom = { syncBrokerClient.leaveRoom() },
                            onTogglePlayback = { desiredPlaying ->
                                val current = room.playback
                                val track = current?.track ?: room.queue.firstOrNull()?.track
                                if (track != null) {
                                    syncBrokerClient.broadcastPlaybackState(
                                        RoomPlaybackState(
                                            track = track,
                                            isPlaying = desiredPlaying,
                                            positionMs = if (desiredPlaying && current?.track?.id != track.id) 0L
                                            else mediaController?.currentPosition?.coerceAtLeast(0L) ?: 0L,
                                            updatedAt = 0L
                                        )
                                    )
                                }
                            },
                            onSeek = { positionMs ->
                                val current = room.playback ?: return@PlayerScreen
                                syncBrokerClient.broadcastPlaybackState(current.copy(positionMs = positionMs, updatedAt = 0L))
                            },
                            onPlayTrack = { track ->
                                syncBrokerClient.broadcastPlaybackState(
                                    RoomPlaybackState(track, isPlaying = true, positionMs = 0L, updatedAt = 0L)
                                )
                            },
                            onSearchTracks = { query -> syncBrokerClient.searchTracks(query) },
                            onListAlbums = { offset -> syncBrokerClient.listAlbums(offset) },
                            onGetAlbumTracks = syncBrokerClient::getAlbumTracks,
                            onAddTrack = syncBrokerClient::enqueueTrack,
                            onRemoveQueueEntry = syncBrokerClient::removeQueueEntry,
                            onReorderQueue = syncBrokerClient::reorderQueue
                        )
                    }
                }
            }
        }
    }
}
