package com.example.ui

import android.content.Context
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.DonorState
import com.example.model.LogEntry
import com.example.model.LogLevel
import com.example.network.WifiNetworkMonitor
import com.example.service.OemAutostartHelper
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.GoogleBluePrimary
import com.example.ui.theme.GoogleGreen
import com.example.ui.theme.GoogleRed
import com.example.ui.theme.GoogleYellow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiagnosticsScreen(
    state: DonorState,
    logEntries: List<LogEntry>,
    selfTestResult: String?,
    isTesting: Boolean,
    onRunSelfTest: () -> Unit,
    onClearLogs: () -> Unit
) {
    val context = LocalContext.current
    var selectedFilter by remember { mutableStateOf<LogLevel?>(null) }

    val isBatteryOptimized = OemAutostartHelper.isBatteryOptimizationIgnored(context)
    val isDataSaverRestricted = OemAutostartHelper.isDataSaverRestricted(context)
    val manufacturer = OemAutostartHelper.getDetectedManufacturer()

    val filteredLogs = remember(logEntries, selectedFilter) {
        if (selectedFilter == null) logEntries else logEntries.filter { it.level == selectedFilter }
    }

    val wifiIp = remember { WifiNetworkMonitor.getWifiIpAddress(context)?.hostAddress ?: "Not detected" }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkSurface)
            .padding(16.dp)
    ) {
        item {
            Text(
                text = "Diagnostics & Kernel",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = "Low-level socket routing and OEM killer diagnostics",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF9AA0A6)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // OEM Background Survival Card
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("oem_survival_card")
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Shield, contentDescription = null, tint = GoogleBluePrimary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Background Survival ($manufacturer)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isBatteryOptimized) GoogleGreen.copy(alpha = 0.2f) else GoogleYellow.copy(alpha = 0.2f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (isBatteryOptimized) "Optimized" else "Restricted",
                                color = if (isBatteryOptimized) GoogleGreen else GoogleYellow,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = if (isBatteryOptimized) {
                            "Battery optimization exemption is ACTIVE. SOCKS5 listeners will persist during screen-off."
                        } else {
                            "OEM battery management may terminate background sockets after 2-5 minutes. Tap below to exempt."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFBDC1C6)
                    )

                    if (isDataSaverRestricted) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "⚠ Data Saver is restricting background cellular data. Please whitelist BoostNet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = GoogleYellow
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedButton(
                        onClick = {
                            val intent = OemAutostartHelper.getAutostartIntent(context)
                            try {
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                // fallback
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp), tint = GoogleBluePrimary)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Configure $manufacturer Background Autostart", color = GoogleBluePrimary, fontSize = 13.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // SOCKS5 Loopback Self-Test Card
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.BugReport, contentDescription = null, tint = GoogleBluePrimary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "SOCKS5 Loopback Self-Test",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Fires a real SOCKS5 client socket through 127.0.0.1:1080 to verify RFC 1929 authentication and cellular socket routing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF9AA0A6)
                    )

                    if (selfTestResult != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        val isPass = selfTestResult.startsWith("PASSED")
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isPass) GoogleGreen.copy(alpha = 0.15f) else GoogleRed.copy(alpha = 0.15f))
                                .border(1.dp, if (isPass) GoogleGreen else GoogleRed, RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Text(
                                text = selfTestResult,
                                color = if (isPass) GoogleGreen else GoogleRed,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = onRunSelfTest,
                        enabled = !isTesting,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GoogleBluePrimary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isTesting) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Black, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Testing Cellular Binding...", color = Color.Black)
                        } else {
                            Icon(Icons.Default.PlayCircle, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Run Local Loopback Test", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Interface Status Summary Card
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Interface Bindings",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Wi-Fi IP (Dual-Stack)", style = MaterialTheme.typography.bodySmall, color = Color(0xFF9AA0A6))
                        Text(wifiIp, style = MaterialTheme.typography.bodySmall, color = Color.White, fontFamily = FontFamily.Monospace)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("SOCKS5 Proxy Port", style = MaterialTheme.typography.bodySmall, color = Color(0xFF9AA0A6))
                        Text("1080 (TCP)", style = MaterialTheme.typography.bodySmall, color = Color.White, fontFamily = FontFamily.Monospace)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Control Channel Port", style = MaterialTheme.typography.bodySmall, color = Color(0xFF9AA0A6))
                        Text("9998 (TCP)", style = MaterialTheme.typography.bodySmall, color = Color.White, fontFamily = FontFamily.Monospace)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("UDP Discovery Beacon", style = MaterialTheme.typography.bodySmall, color = Color(0xFF9AA0A6))
                        Text("9999 (UDP)", style = MaterialTheme.typography.bodySmall, color = Color.White, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Logs Header & Filter Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Kernel Event Stream (${filteredLogs.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClearLogs) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear logs", tint = Color.LightGray)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }

        // Live Log Entries
        if (filteredLogs.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 30.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No kernel events logged yet.", color = Color(0xFF9AA0A6), style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            items(filteredLogs, key = { it.id }) { log ->
                LogItemRow(log)
            }
        }
    }
}

@Composable
private fun LogItemRow(log: LogEntry) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    val timeString = timeFormat.format(Date(log.timestamp))

    val levelColor = when (log.level) {
        LogLevel.INFO -> GoogleBluePrimary
        LogLevel.WARN -> GoogleYellow
        LogLevel.ERROR -> GoogleRed
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = timeString,
            fontSize = 10.sp,
            color = Color(0xFF9AA0A6),
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(76.dp)
        )

        Text(
            text = "[${log.tag}]",
            fontSize = 10.sp,
            color = levelColor,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(90.dp)
        )

        Text(
            text = log.message,
            fontSize = 11.sp,
            color = Color(0xFFE8EAED),
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )
    }
}
