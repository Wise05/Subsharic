package com.zevan.soundparty.ui.components

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zevan.soundparty.sync.ClockSyncState
import com.zevan.soundparty.sync.ClockSynchronizer
import com.zevan.soundparty.sync.WebSocketConnectionState
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * High-precision debug overlay showing Cristian's clock synchronization metrics:
 * - Live ServerTime = SystemClock.elapsedRealtime() + θ
 * - Live RTT, Smoothed RTT, Min RTT
 * - Clock offset θ (±ms)
 * - Connection status
 * - Manual "Sync Now" trigger
 */
@Composable
fun SyncDebugOverlay(
    synchronizer: ClockSynchronizer,
    connectionState: WebSocketConnectionState,
    onSyncNowClicked: () -> Unit,
    modifier: Modifier = Modifier
) {

    val syncState by synchronizer.syncState.collectAsState()
    var isExpanded by remember { mutableStateOf(false) }

    // Live updating clock ticks (every 50ms) for real-time millisecond visualization
    var currentServerTime by remember { mutableStateOf(synchronizer.currentServerTimeMillis()) }
    var currentElapsedRealtime by remember { mutableStateOf(SystemClock.elapsedRealtime()) }

    LaunchedEffect(Unit) {
        while (true) {
            currentServerTime = synchronizer.currentServerTimeMillis()
            currentElapsedRealtime = SystemClock.elapsedRealtime()
            delay(50L)
        }
    }

    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xEE1E1E24) // Semi-transparent dark surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Header: Status Badge + Live Offset + Expand toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Status indicator dot
                    val statusColor = when (connectionState) {
                        WebSocketConnectionState.CONNECTED -> if (syncState.isSynchronized) Color(
                            0xFF4CAF50
                        ) else Color(0xFFFFC107)

                        WebSocketConnectionState.CONNECTING -> Color(0xFF2196F3)
                        WebSocketConnectionState.ERROR -> Color(0xFFF44336)
                        WebSocketConnectionState.DISCONNECTED -> Color(0xFF9E9E9E)
                    }

                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = if (syncState.isSynchronized) "SYNCED" else connectionState.name,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                // Header θ offset summary
                val thetaSign = if (syncState.theta >= 0) "+" else ""
                Text(
                    text = "θ: $thetaSign${syncState.theta} ms | RTT: ${syncState.latestRttMs} ms",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF81D4FA)
                )

                Text(
                    text = if (isExpanded) "▲" else "▼",
                    color = Color.LightGray,
                    fontSize = 12.sp
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    HorizontalDivider(color = Color(0xFF33333E), thickness = 1.dp)

                    Spacer(modifier = Modifier.height(10.dp))

                    // Live Timestamps
                    MetricRow(
                        label = "Server Time (Epoch)",
                        value = "${timeFormatter.format(Date(currentServerTime))} (${currentServerTime})"
                    )

                    MetricRow(
                        label = "Elapsed Realtime",
                        value = "${currentElapsedRealtime} ms"
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // RTT metrics
                    MetricRow(
                        label = "Latest RTT",
                        value = "${syncState.latestRttMs} ms"
                    )

                    MetricRow(
                        label = "Smoothed RTT (EMA)",
                        value = "${syncState.smoothedRttMs} ms"
                    )

                    MetricRow(
                        label = "Min Observed RTT",
                        value = if (syncState.minRttMs == Long.MAX_VALUE) "--" else "${syncState.minRttMs} ms"
                    )

                    MetricRow(
                        label = "Total Samples",
                        value = "${syncState.sampleCount}"
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Manual Sync Trigger Button
                    Button(
                        onClick = onSyncNowClicked,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF3F51B5)
                        ),
                        enabled = connectionState == WebSocketConnectionState.CONNECTED
                    ) {
                        Text(
                            text = "Sync Now (Burst)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = Color(0xFFAAAAAA)
        )
        Text(
            text = value,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White
        )
    }
}
