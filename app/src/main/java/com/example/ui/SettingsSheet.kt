package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.model.DonorConfig
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.GoogleBluePrimary

@Composable
fun SettingsSheet(
    currentConfig: DonorConfig,
    onDismiss: () -> Unit,
    onSave: (DonorConfig) -> Unit
) {
    var name by remember { mutableStateOf(currentConfig.displayName) }
    var carrier by remember { mutableStateOf(currentConfig.carrier) }
    var tech by remember { mutableStateOf(currentConfig.tech) }
    var quotaGb by remember { mutableFloatStateOf((currentConfig.quotaLimitBytes / (1024f * 1024f * 1024f)).coerceIn(0.1f, 20f)) }

    val carriers = listOf("Jio", "Airtel", "Vi", "BSNL", "Other")
    val techs = listOf("4G", "5G")

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = DarkSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    text = "Node Settings",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Display Name
                Text("Display Name", style = MaterialTheme.typography.labelMedium, color = Color(0xFF9AA0A6))
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Carrier Selector
                Text("Mobile Carrier", style = MaterialTheme.typography.labelMedium, color = Color(0xFF9AA0A6))
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    carriers.forEach { c ->
                        val isSelected = c.equals(carrier, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) GoogleBluePrimary else DarkSurfaceElevated)
                                .clickable { carrier = c }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = c,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) Color.Black else Color.White
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Technology Selector (4G / 5G)
                Text("Radio Technology", style = MaterialTheme.typography.labelMedium, color = Color(0xFF9AA0A6))
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    techs.forEach { t ->
                        val isSelected = t.equals(tech, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) GoogleBluePrimary else DarkSurfaceElevated)
                                .clickable { tech = t }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = t,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) Color.Black else Color.White
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Data Quota Cap Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Daily Quota Cap", style = MaterialTheme.typography.labelMedium, color = Color(0xFF9AA0A6))
                    Text(
                        text = "%.1f GB".format(quotaGb),
                        style = MaterialTheme.typography.labelMedium,
                        color = GoogleBluePrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
                Slider(
                    value = quotaGb,
                    onValueChange = { quotaGb = it },
                    valueRange = 0.5f..20f,
                    steps = 39,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = {
                            val bytes = (quotaGb * 1024L * 1024L * 1024L).toLong()
                            onSave(
                                currentConfig.copy(
                                    displayName = name.trim().ifEmpty { currentConfig.displayName },
                                    carrier = carrier,
                                    tech = tech,
                                    quotaLimitBytes = bytes
                                )
                            )
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}
