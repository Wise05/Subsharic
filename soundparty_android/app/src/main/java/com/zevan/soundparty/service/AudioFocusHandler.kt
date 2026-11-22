package com.zevan.soundparty.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import androidx.media3.common.Player

/** Owns audio focus for the media service and leaves interrupted playback paused. */
class AudioFocusHandler(context: Context, private val player: Player) : AutoCloseable {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var ownsFocus = false

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player.pause()
                // Deliberately do not resume: the user can press Play after the interruption.
                AudioManager.AUDIOFOCUS_GAIN -> Unit
            }
        }
        .build()

    private val playerListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) requestFocus() else abandonFocus()
        }
    }

    init {
        player.addListener(playerListener)
    }

    private fun requestFocus() {
        if (ownsFocus) return
        ownsFocus = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.d(TAG, "Audio focus request ${if (ownsFocus) "granted" else "denied"}")
        if (!ownsFocus) player.pause()
    }

    private fun abandonFocus() {
        if (!ownsFocus) return
        audioManager.abandonAudioFocusRequest(focusRequest)
        ownsFocus = false
    }

    override fun close() {
        player.removeListener(playerListener)
        abandonFocus()
    }

    private companion object {
        const val TAG = "AudioFocusHandler"
    }
}
