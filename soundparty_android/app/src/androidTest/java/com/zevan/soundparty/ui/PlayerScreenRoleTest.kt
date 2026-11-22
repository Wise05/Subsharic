package com.zevan.soundparty.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zevan.soundparty.sync.RoomPlaybackState
import com.zevan.soundparty.sync.RoomQueueEntry
import com.zevan.soundparty.sync.RoomSessionState
import com.zevan.soundparty.sync.RoomTrack
import com.zevan.soundparty.sync.SyncBrokerClient
import com.zevan.soundparty.ui.screens.PlayerScreen
import com.zevan.soundparty.ui.theme.SoundPartyTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerScreenRoleTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val brokerClient = SyncBrokerClient("ws://127.0.0.1:3000/ws")

    @After
    fun cleanup() {
        brokerClient.destroy()
    }

    @Test
    fun hostCanReorderAndRemoveQueueEntries() {
        composeRule.setContent { PlayerScreenForRole(isHost = true) }
        composeRule.onNodeWithTag("player-screen-list").performScrollToNode(hasText("Queue"))
        composeRule.onNodeWithTag("player-screen-list").performScrollToNode(hasText("Up"))
        composeRule.onNodeWithText("Up").assertIsDisplayed()
        composeRule.onNodeWithText("Remove").assertIsDisplayed()
    }

    @Test
    fun memberCannotReorderOrRemoveQueueEntries() {
        composeRule.setContent { PlayerScreenForRole(isHost = false) }
        composeRule.onNodeWithText("Playback is controlled by the room host.").assertIsDisplayed()
        composeRule.onNodeWithTag("player-screen-list").performScrollToNode(hasText("Queue"))
        composeRule.onNodeWithText("Queued").assertIsDisplayed()
        composeRule.onAllNodesWithText("Up").assertCountEquals(0)
        composeRule.onAllNodesWithText("Remove").assertCountEquals(0)
    }

    @androidx.compose.runtime.Composable
    private fun PlayerScreenForRole(isHost: Boolean) {
        val track = RoomTrack("track-1", "Track", artist = "Artist", durationMs = 120_000)
        val room = RoomSessionState(
            roomId = "room-code",
            hostId = if (isHost) "client" else "host",
            clientId = "client",
            playback = RoomPlaybackState(track, isPlaying = false, positionMs = 0, updatedAt = 0),
            queue = listOf(RoomQueueEntry("entry-1", track))
        )
        SoundPartyTheme {
            PlayerScreen(
                room = room,
                serverError = null,
                controller = null,
                syncBrokerClient = brokerClient,
                bluetoothOffsetMs = 0,
                onBluetoothOffsetChange = {},
                onLeaveRoom = {},
                onTogglePlayback = {},
                onSeek = {},
                onPlayTrack = {},
                onSearchTracks = { emptyList() },
                onAddTrack = { true },
                onRemoveQueueEntry = { true },
                onReorderQueue = { true }
            )
        }
    }
}
