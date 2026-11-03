package com.example.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.DonorConfig
import com.example.model.DonorState
import com.example.model.TelemetryStats
import com.example.ui.components.CarrierBadge
import com.example.ui.components.QuotaRing
import com.example.ui.components.SpeedSparkline
import com.example.ui.theme.DarkOutline
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.GoogleBluePrimary
import com.example.ui.theme.GoogleGreen
import com.example.ui.theme.GoogleOrange
import com.example.ui.theme.GoogleRed
import com.example.ui.theme.GoogleYellow
import com.example.ui.theme.StateActive
import com.example.ui.theme.StateDraining
import com.example.ui.theme.StateStopped
import com.example.ui.theme.StateThrottled

@Composable
fun HomeScreen(
    state: DonorState,
    config: DonorConfig,
    stats: TelemetryStats,
    speedHistory: List<Double>,
    onToggleService: () -> Unit,
    onShowPairing: () -> Unit,
    onShowSettings: () -> Unit
) {
    val scrollState = rememberScrollState()

    val stateColor by animateColorAsState(
        targetValue = when (state) {
            DonorState.ACTIVE -> StateActive
            DonorState.THROTTLED -> StateThrottled
            DonorState.DRAINING -> StateDraining
            DonorState.STOPPED -> StateStopped
        },
        label = "stateColor"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkSurface)
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        // Top Bar: App title & Quick Settings Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "BoostNet Donor",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = config.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF9AA0A6)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onShowPairing,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("pairing_button")
                ) {
                    Icon(Icons.Default.QrCode2, contentDescription = "Pairing QR", tint = GoogleBluePrimary)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Pair", color = GoogleBluePrimary)
                }

                OutlinedButton(
                    onClick = onShowSettings,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("settings_button")
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.LightGray)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Hero Status Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, stateColor.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                .testTag("hero_status_card")
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(stateColor)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = state.label.uppercase(),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = stateColor,
                            letterSpacing = 1.sp
                        )
                    }

                    CarrierBadge(carrier = config.carrier, tech = config.tech)
                }

                Spacer(modifier = Modifier.height(12.dp))

                val headline = when (state) {
                    DonorState.ACTIVE -> "Relaying traffic via ${config.carrier} ${config.tech}"
                    DonorState.THROTTLED -> "Throttled: ${stats.stateReason}"
                    DonorState.DRAINING -> "Draining: Completing active connections"
                    DonorState.STOPPED -> "Donor node is idle"
                }

                Text(
                    text = headline,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )

                if (state != DonorState.STOPPED) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Forcing all laptop proxy sockets through mobile radio tower",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF9AA0A6)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Throughput Speed Card with Sparkline
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column {
                        Text(
                            text = "Throughput",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF9AA0A6)
                        )
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = "%.1f".format(stats.instantaneousMbps),
                                style = MaterialTheme.typography.displayLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Mbps",
                                style = MaterialTheme.typography.titleLarge,
                                color = GoogleBluePrimary,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "Total Relayed",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF9AA0A6)
                        )
                        val mbRelayed = stats.bytesRelayedSession / (1024 * 1024)
                        Text(
                            text = if (mbRelayed >= 1024) "%.2f GB".format(mbRelayed / 1024.0) else "$mbRelayed MB",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Real-time speed sparkline
                SpeedSparkline(
                    history = speedHistory,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(65.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Telemetry Row: Connections, Battery, Thermal, Duration
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Active Connections
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated),
                modifier = Modifier.weight(1f)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Cable, contentDescription = null, tint = GoogleBluePrimary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Sockets", style = MaterialTheme.typography.labelSmall, color = Color(0xFF9AA0A6))
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "${stats.activeConnections} / 32",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            // Battery
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated),
                modifier = Modifier.weight(1f)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Bolt, contentDescription = null, tint = if (stats.isCharging) GoogleGreen else GoogleYellow, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Battery", style = MaterialTheme.typography.labelSmall, color = Color(0xFF9AA0A6))
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "${stats.batteryPercent}%${if (stats.isCharging) " ⚡" else ""}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (stats.batteryPercent <= 15 && !stats.isCharging) GoogleRed else Color.White
                    )
                }
            }

            // Thermal
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated),
                modifier = Modifier.weight(1f)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Thermostat, contentDescription = null, tint = GoogleOrange, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Thermal", style = MaterialTheme.typography.labelSmall, color = Color(0xFF9AA0A6))
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stats.thermalStatus,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = when (stats.thermalStatus) {
                            "SEVERE", "CRITICAL" -> GoogleRed
                            "MODERATE" -> GoogleOrange
                            else -> GoogleGreen
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Data Quota Consumption Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Data Quota",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF9AA0A6)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val usedMb = stats.bytesRelayedSession / (1024 * 1024)
                    val limitMb = config.quotaLimitBytes / (1024 * 1024)
                    Text(
                        text = if (limitMb >= 1024) "%.2f / %.1f GB".format(usedMb / 1024.0, limitMb / 1024.0) else "$usedMb / $limitMb MB",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val remMb = stats.quotaRemainingBytes / (1024 * 1024)
                    Text(
                        text = "${if (remMb >= 1024) "%.2f GB".format(remMb / 1024.0) else "$remMb MB"} remaining",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (remMb <= 100) GoogleRed else Color(0xFF9AA0A6)
                    )
                }

                QuotaRing(
                    usedBytes = stats.bytesRelayedSession,
                    totalBytes = config.quotaLimitBytes
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Primary Start / Stop Node Action Button
        val isRunning = state != DonorState.STOPPED
        Button(
            onClick = onToggleService,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) GoogleRed else GoogleBluePrimary
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .testTag("toggle_node_button")
        ) {
            Icon(
                imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = null,
                tint = if (isRunning) Color.White else Color.Black
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isRunning) "Stop Donor Node" else "Start Donor Node",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (isRunning) Color.White else Color.Black
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
