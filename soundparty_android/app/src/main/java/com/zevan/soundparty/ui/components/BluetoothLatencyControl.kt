package com.zevan.soundparty.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zevan.soundparty.settings.BluetoothLatencyPreferences
import kotlin.math.roundToInt

@Composable
fun BluetoothLatencyControl(
    offsetMs: Int,
    onOffsetChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("Bluetooth latency", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${if (offsetMs > 0) "+" else ""}${offsetMs} ms",
                    modifier = Modifier.weight(1f)
                )
            }
            Slider(
                value = offsetMs.toFloat(),
                onValueChange = { onOffsetChange(it.roundToInt()) },
                valueRange = BluetoothLatencyPreferences.MIN_OFFSET_MS.toFloat()..
                    BluetoothLatencyPreferences.MAX_OFFSET_MS.toFloat(),
                steps = BluetoothLatencyPreferences.MAX_OFFSET_MS * 2 - 1
            )
            Text(
                "Positive values start this device earlier to compensate for delayed Bluetooth output.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
