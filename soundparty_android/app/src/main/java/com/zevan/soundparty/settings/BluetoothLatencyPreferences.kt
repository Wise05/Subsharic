package com.zevan.soundparty.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Per-device Bluetooth output compensation, persisted across app launches. */
class BluetoothLatencyPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _offsetMs = MutableStateFlow(normalizeOffsetMs(preferences.getInt(KEY_OFFSET_MS, 0)))
    val offsetMs: StateFlow<Int> = _offsetMs.asStateFlow()

    fun setOffsetMs(value: Int) {
        val bounded = normalizeOffsetMs(value)
        preferences.edit().putInt(KEY_OFFSET_MS, bounded).apply()
        _offsetMs.value = bounded
    }

    companion object {
        const val MIN_OFFSET_MS = -200
        const val MAX_OFFSET_MS = 200
        private const val PREFERENCES_NAME = "playback_settings"
        private const val KEY_OFFSET_MS = "bluetooth_latency_offset_ms"

        fun normalizeOffsetMs(value: Int): Int = value.coerceIn(MIN_OFFSET_MS, MAX_OFFSET_MS)
    }
}
