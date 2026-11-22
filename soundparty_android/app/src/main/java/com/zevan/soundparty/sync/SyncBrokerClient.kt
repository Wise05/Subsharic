package com.zevan.soundparty.sync

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers.IO
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.HttpUrl.Companion.toHttpUrl

enum class WebSocketConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

/**
 * Manages WebSocket transport to the Sync Broker (/ws) and wires NTP timestamp pings to ClockSynchronizer.
 *
 * @param wsUrl The WebSocket URL, e.g., "ws://100.65.130.65:3000/ws"
 * @param okHttpClient The shared OkHttpClient instance
 */
class SyncBrokerClient(
    private val wsUrl: String,
    private val okHttpClient: OkHttpClient = OkHttpClient()
) {
    private val gson = Gson()
    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow(WebSocketConnectionState.DISCONNECTED)
    val connectionState: StateFlow<WebSocketConnectionState> = _connectionState.asStateFlow()

    private val _playActions = MutableSharedFlow<PlayAction>(extraBufferCapacity = 8)
    val playActions: SharedFlow<PlayAction> = _playActions

    private val _roomState = MutableStateFlow<RoomSessionState?>(null)
    val roomState: StateFlow<RoomSessionState?> = _roomState.asStateFlow()

    private val _serverError = MutableStateFlow<String?>(null)
    val serverError: StateFlow<String?> = _serverError.asStateFlow()
    private val _playbackPreparation = MutableStateFlow<String?>(null)
    val playbackPreparation: StateFlow<String?> = _playbackPreparation.asStateFlow()

    private var webSocket: WebSocket? = null

    val clockSynchronizer = ClockSynchronizer(
        sendPing = { clientTx ->
            sendJson(
                JsonObject().apply {
                    addProperty("type", "SYNC_PING")
                    addProperty("clientTx", clientTx)
                }
            )
        }
    )

    fun connect() {
        if (_connectionState.value == WebSocketConnectionState.CONNECTED ||
            _connectionState.value == WebSocketConnectionState.CONNECTING
        ) {
            return
        }

        _connectionState.value = WebSocketConnectionState.CONNECTING

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connectionState.value = WebSocketConnectionState.CONNECTED
                _serverError.value = null
                clockSynchronizer.start(clientScope)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JsonParser.parseString(text).asJsonObject
                    when (json.get("type")?.asString) {
                        "ERROR" -> {
                            _serverError.value = json.get("message")?.asString ?: "The broker rejected the request."
                            if (json.get("code")?.asString == "AUDIO_PREFETCH_FAILED") {
                                _playbackPreparation.value = null
                            }
                        }
                        "PLAYBACK_PREPARING" -> {
                            _playbackPreparation.value = "Preparing audio on the broker…"
                        }
                        "SYNC_PONG" -> {
                            val clientTx = json.get("clientTx")?.asLong ?: return
                            val serverRx = json.get("serverRx")?.asLong ?: return
                            val serverTx = json.get("serverTx")?.asLong ?: return
                            clockSynchronizer.onSyncPongReceived(clientTx, serverRx, serverTx)
                        }
                        "PLAY" -> {
                            val actionTime = json.get("actionTime")?.takeIf { it.isJsonPrimitive }
                                ?.asLong ?: return
                            val positionMs = json.get("positionMs")?.takeIf { it.isJsonPrimitive }
                                ?.asLong ?: 0L
                            val mediaUri = json.get("mediaUri")?.takeIf { it.isJsonPrimitive }
                                ?.asString ?: return
                            if (actionTime >= 0L && positionMs >= 0L && mediaUri.isNotBlank()) {
                                _playActions.tryEmit(PlayAction(actionTime, positionMs, mediaUri))
                            }
                        }
                        "ROOM_CREATED" -> {
                            val roomId = json.get("roomId")?.asString ?: return
                            val hostId = json.get("hostId")?.asString ?: return
                            val clientId = json.get("clientId")?.asString ?: return
                            _roomState.value = RoomSessionState(roomId, hostId, clientId)
                        }
                        "ROOM_JOINED" -> {
                            val roomId = json.get("roomId")?.asString ?: return
                            val hostId = json.get("hostId")?.asString ?: return
                            val clientId = json.get("clientId")?.asString ?: return
                            _roomState.value = RoomSessionState(
                                roomId = roomId,
                                hostId = hostId,
                                clientId = clientId,
                                playback = json.get("state")?.takeUnless { it.isJsonNull }?.asJsonObject?.let(::parsePlaybackState),
                                queue = json.getAsJsonArray("queue")?.mapNotNull { runCatching { parseQueueEntry(it.asJsonObject) }.getOrNull() }.orEmpty()
                            )
                        }
                        "STATE_UPDATED" -> {
                            val current = _roomState.value ?: return
                            if (json.get("roomId")?.asString != current.roomId) return
                            val playback = json.get("state")?.asJsonObject?.let(::parsePlaybackState) ?: return
                            _playbackPreparation.value = null
                            _roomState.value = current.copy(playback = playback)
                        }
                        "QUEUE_UPDATED" -> {
                            val current = _roomState.value ?: return
                            if (json.get("roomId")?.asString != current.roomId) return
                            val queue = json.getAsJsonArray("queue")?.mapNotNull {
                                runCatching { parseQueueEntry(it.asJsonObject) }.getOrNull()
                            } ?: return
                            Log.d("SyncBrokerClient", "Queue update received: ${queue.size} tracks")
                            _roomState.value = current.copy(queue = queue)
                        }
                        "MEMBER_LEFT" -> {
                            val current = _roomState.value ?: return
                            if (json.get("roomId")?.asString != current.roomId) return
                            val hostId = json.get("hostId")?.asString ?: current.hostId
                            _roomState.value = current.copy(hostId = hostId)
                        }
                        "ROOM_LEFT" -> {
                            if (json.get("roomId")?.asString == _roomState.value?.roomId) _roomState.value = null
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _connectionState.value = WebSocketConnectionState.DISCONNECTED
                _roomState.value = null
                _playbackPreparation.value = null
                clockSynchronizer.stop()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _connectionState.value = WebSocketConnectionState.ERROR
                _roomState.value = null
                _playbackPreparation.value = null
                _serverError.value = "Broker connection failed: ${t.localizedMessage ?: "unknown network error"}"
                Log.e("SyncBrokerClient", _serverError.value.orEmpty(), t)
                clockSynchronizer.stop()
            }
        })
    }

    fun disconnect() {
        clockSynchronizer.stop()
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _roomState.value = null
        _playbackPreparation.value = null
        _connectionState.value = WebSocketConnectionState.DISCONNECTED
    }

    private fun sendJson(json: JsonObject): Boolean {
        val socket = webSocket
        if (socket == null || _connectionState.value != WebSocketConnectionState.CONNECTED) {
            _serverError.value = "Not connected to the broker; the command was not sent."
            return false
        }
        Log.d("SyncBrokerClient", "Sending WebSocket command: ${json.get("type")?.asString ?: "unknown"}")
        val sent = socket.send(gson.toJson(json))
        _serverError.value = if (sent) null else "The broker connection is closing; the command was not sent."
        return sent
    }

    fun createRoom(): Boolean = sendCommand("CREATE_ROOM")

    fun joinRoom(roomId: String): Boolean {
        if (roomId.isBlank()) return false
        return sendCommand("JOIN_ROOM", "roomId" to roomId.trim())
    }

    fun leaveRoom(): Boolean = sendCommand("LEAVE_ROOM")

    fun broadcastPlaybackState(state: RoomPlaybackState): Boolean {
        val track = state.track?.let(::serializeTrack)
        val playback = JsonObject().apply {
            if (track == null) add("track", com.google.gson.JsonNull.INSTANCE) else add("track", track)
            addProperty("isPlaying", state.isPlaying)
            addProperty("positionMs", state.positionMs)
            addProperty("updatedAt", state.updatedAt)
        }
        return sendCommand("BROADCAST_STATE", "state" to playback)
    }

    fun enqueueTrack(track: RoomTrack): Boolean = sendCommand(
        "ADD_TO_QUEUE",
        "track" to serializeTrack(track)
    )

    fun removeQueueEntry(queueId: String): Boolean = sendCommand("REMOVE_FROM_QUEUE", "queueId" to queueId)

    fun reorderQueue(queueIds: List<String>): Boolean = sendCommand(
        "REORDER_QUEUE",
        "queueIds" to gson.toJsonTree(queueIds).asJsonArray
    )

    suspend fun searchTracks(query: String): List<RoomTrack> = withContext(IO) {
        val baseUrl = brokerHttpBaseUrl().toHttpUrl().newBuilder()
            .addPathSegments("api/search")
            .addQueryParameter("q", query)
            .build()
        val request = Request.Builder().url(baseUrl).get().build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("Track search failed (${response.code})")
            val body = response.body?.string() ?: return@withContext emptyList()
            JsonParser.parseString(body).asJsonArray.mapNotNull { element ->
                runCatching { parseTrack(element.asJsonObject) }.getOrNull()
            }
        }
    }

    suspend fun listAlbums(offset: Int, limit: Int = 50): List<MusicAlbum> = withContext(IO) {
        val url = brokerHttpBaseUrl().toHttpUrl().newBuilder()
            .addPathSegments("api/albums")
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("size", limit.toString())
            .build()
        val request = Request.Builder().url(url).get().build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("Album listing failed (${response.code})")
            val body = response.body?.string() ?: return@withContext emptyList()
            JsonParser.parseString(body).asJsonArray.mapNotNull { element ->
                runCatching {
                    val json = element.asJsonObject
                    MusicAlbum(
                        id = json.get("id").asString,
                        title = json.get("title").asString,
                        artist = json.get("artist")?.takeUnless { it.isJsonNull }?.asString,
                        artworkUrl = json.get("artworkUrl")?.takeUnless { it.isJsonNull }?.asString,
                        songCount = json.get("songCount")?.takeUnless { it.isJsonNull }?.asInt
                    )
                }.getOrNull()
            }
        }
    }

    suspend fun getAlbumTracks(albumId: String): List<RoomTrack> = withContext(IO) {
        val url = brokerHttpBaseUrl().toHttpUrl().newBuilder()
            .addPathSegments("api/albums")
            .addPathSegment(albumId)
            .build()
        val request = Request.Builder().url(url).get().build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("Album tracks failed (${response.code})")
            val body = response.body?.string() ?: return@withContext emptyList()
            JsonParser.parseString(body).asJsonObject.getAsJsonArray("tracks")
                ?.mapNotNull { element -> runCatching { parseTrack(element.asJsonObject) }.getOrNull() }
                .orEmpty()
        }
    }

    fun streamUrlForTrack(trackId: String): String {
        return "${brokerHttpBaseUrl()}/api/stream/${android.net.Uri.encode(trackId)}"
    }

    fun resolveArtworkUrl(artworkUrl: String?): String? = artworkUrl?.let {
        if (it.startsWith("http://") || it.startsWith("https://")) it
        else brokerHttpBaseUrl() + if (it.startsWith('/')) it else "/$it"
    }

    private fun brokerHttpBaseUrl(): String = wsUrl
        .replaceFirst("wss://", "https://")
        .replaceFirst("ws://", "http://")
        .substringBeforeLast("/ws")
        .trimEnd('/')

    private fun sendCommand(type: String, vararg fields: Pair<String, Any>): Boolean =
        sendJson(JsonObject().apply {
            addProperty("type", type)
            fields.forEach { (key, value) ->
                when (value) {
                    is String -> addProperty(key, value)
                    is Number -> addProperty(key, value)
                    is Boolean -> addProperty(key, value)
                    is JsonObject -> add(key, value)
                    is com.google.gson.JsonArray -> add(key, value)
                }
            }
        })

    private fun parsePlaybackState(json: JsonObject): RoomPlaybackState {
        val track = json.get("track")?.takeUnless { it.isJsonNull }?.asJsonObject?.let { trackJson ->
            val id = trackJson.get("id")?.asString ?: return@let null
            parseTrack(trackJson)
        }
        return RoomPlaybackState(
            track = track,
            isPlaying = json.get("isPlaying")?.asBoolean ?: false,
            positionMs = (json.get("positionMs")?.asLong ?: 0L).coerceAtLeast(0L),
            updatedAt = (json.get("updatedAt")?.asLong ?: 0L).coerceAtLeast(0L)
        )
    }

    private fun parseQueueEntry(json: JsonObject): RoomQueueEntry = RoomQueueEntry(
        queueId = json.get("queueId")?.asString ?: error("Missing queueId"),
        track = parseTrack(json.getAsJsonObject("track"))
    )

    private fun parseTrack(json: JsonObject): RoomTrack {
        val id = json.get("id")?.asString ?: error("Missing track id")
        return RoomTrack(
            id = id,
            title = json.get("title")?.asString ?: id,
            artist = json.get("artist")?.takeUnless { it.isJsonNull }?.asString,
            album = json.get("album")?.takeUnless { it.isJsonNull }?.asString,
            durationMs = json.get("durationMs")?.takeUnless { it.isJsonNull }?.asLong,
            artworkUrl = json.get("artworkUrl")?.takeUnless { it.isJsonNull }?.asString
        )
    }

    private fun serializeTrack(track: RoomTrack): JsonObject = JsonObject().apply {
        addProperty("id", track.id)
        addProperty("title", track.title)
        track.artist?.let { addProperty("artist", it) }
        track.album?.let { addProperty("album", it) }
        track.durationMs?.let { addProperty("durationMs", it) }
        track.artworkUrl?.let { addProperty("artworkUrl", it) }
    }

    fun triggerSyncBurst() {
        clientScope.launch {
            clockSynchronizer.syncNow()
        }
    }

    fun destroy() {
        disconnect()
        clientScope.cancel()
    }
}
