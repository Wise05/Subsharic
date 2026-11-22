package com.zevan.soundparty.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zevan.soundparty.sync.WebSocketConnectionState
import com.zevan.soundparty.ui.components.BluetoothLatencyControl

@Composable
fun LobbyScreen(
    connectionState: WebSocketConnectionState,
    serverError: String?,
    onCreateRoom: () -> Unit,
    onJoinRoom: (String) -> Boolean,
    bluetoothOffsetMs: Int,
    onBluetoothOffsetChange: (Int) -> Unit
) {
    var roomCode by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("SoundParty", style = MaterialTheme.typography.headlineLarge)
        Text("Connection: ${connectionState.name.lowercase().replace('_', ' ')}")
        Spacer(Modifier.height(24.dp))
        Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text("Start a listening party", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onCreateRoom,
                    enabled = connectionState == WebSocketConnectionState.CONNECTED,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Create room")
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = roomCode,
                    onValueChange = { roomCode = it },
                    label = { Text("Room code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                serverError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onJoinRoom(roomCode) },
                    enabled = roomCode.isNotBlank() && connectionState == WebSocketConnectionState.CONNECTED,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Join room")
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        BluetoothLatencyControl(
            offsetMs = bluetoothOffsetMs,
            onOffsetChange = onBluetoothOffsetChange,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
