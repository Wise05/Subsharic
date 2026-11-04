
package com.zevan.soundparty

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.zevan.soundparty.service.MediaPlaybackService
import com.zevan.soundparty.ui.theme.SoundPartyTheme

class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController by mutableStateOf<MediaController?>(null)

    override fun onStart() {
        super.onStart()

        val sessionToken = SessionToken(
            this,
            ComponentName(this, MediaPlaybackService::class.java)
        )

        val future = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture = future

        future.addListener({
            // Ignore callbacks from a previous activity lifecycle.
            if (controllerFuture !== future) return@addListener

            try {
                mediaController = future.get()
            } catch (e: Exception) {
                mediaController = null
                e.printStackTrace()
            }
        }, MoreExecutors.directExecutor())
    }

    override fun onStop() {
        controllerFuture?.let {
            MediaController.releaseFuture(it)
        }

        controllerFuture = null
        mediaController = null

        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            SoundPartyTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PlayerTestScreen(
                        controller = mediaController,
                        onPlayStream = { streamUrl ->
                            mediaController?.let { controller ->
                                val mediaItem = MediaItem.fromUri(streamUrl)
                                controller.setMediaItem(mediaItem)
                                controller.prepare()
                                controller.play()
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun PlayerTestScreen(
    controller: MediaController?,
    onPlayStream: (String) -> Unit
) {
    var isPlaying by remember { mutableStateOf(false) }

    DisposableEffect(controller) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }

        controller?.addListener(listener)

        onDispose {
            controller?.removeListener(listener)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (isPlaying) "Playing in Background Service" else "Player Stopped",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                if (isPlaying) {
                    controller?.pause()
                } else {
                    val testStreamUrl = "http://{my url}/rest/stream.view?id=sLolVQp4QkKvnM11SS5zlO&u=Zevan&t=36d895251fc75758ca3325a104749e98&s=3fa874&v=1.8.0&c=SoundParty"
                    onPlayStream(testStreamUrl)
                }
            },
            enabled = controller != null
        ) {
            Text(if (isPlaying) "Pause" else "Play Test Track")
        }
    }
}
