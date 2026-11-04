package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.crypto.CryptoManager
import com.example.model.DonorConfig
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.GoogleBluePrimary
import com.example.ui.theme.GoogleRed

@Composable
fun PairingDialog(
    config: DonorConfig,
    secret: String,
    isPaired: Boolean = false,
    onDismiss: () -> Unit,
    onRegenerateSecret: () -> Unit,
    onUnpair: () -> Unit = {},
    onOpenScanner: () -> Unit = {}
) {
    val context = LocalContext.current
    val pairingCode = CryptoManager.getPairingCode(secret)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = DarkSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Laptop Pairing",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.LightGray)
                    }
                }

                                if (isPaired) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0F3822))
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF34A853), modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Phone is Paired & Active", color = Color(0xFF34A853), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Simulated QR Code Matrix derived deterministically from secret
                QrCodeCanvas(
                    data = "boostnet://${config.nodeId}?code=$pairingCode&port=${config.socksPort}&ctrl=${config.ctrlPort}",
                    modifier = Modifier
                        .size(160.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .padding(12.dp)
                )

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = "Pairing Code",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFF9AA0A6)
                )

                Spacer(modifier = Modifier.height(4.dp))

                // High-visibility alphanumeric code box
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF121316))
                        .padding(horizontal = 24.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = pairingCode,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = GoogleBluePrimary,
                        letterSpacing = 4.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Type this 6-character code into BoostNet Console on your laptop.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFBDC1C6),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                Button(
                    onClick = {
                        onDismiss()
                        onOpenScanner()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GoogleBluePrimary),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Scan Laptop QR (Recommended)", fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("BoostNet Token", secret)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Full token copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy Token", fontSize = 12.sp)
                    }

                    Button(
                        onClick = {
                            onRegenerateSecret()
                            Toast.makeText(context, "Generated new pairing secret", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = GoogleRed.copy(alpha = 0.2f)),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = GoogleRed, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Reset Key", color = GoogleRed, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun QrCodeCanvas(
    data: String,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val sizePx = size.width
        val modules = 21 // standard version 1 QR grid size
        val moduleSize = sizePx / modules
        val hash = data.hashCode()

        for (row in 0 until modules) {
            for (col in 0 until modules) {
                // Draw 3 position detection patterns (corners)
                val isFinderPattern = (row in 0..6 && col in 0..6) ||
                        (row in 0..6 && col in (modules - 7) until modules) ||
                        (row in (modules - 7) until modules && col in 0..6)

                val isFilled = if (isFinderPattern) {
                    val r = if (row >= modules - 7) row - (modules - 7) else row
                    val c = if (col >= modules - 7) col - (modules - 7) else col
                    (r == 0 || r == 6 || c == 0 || c == 6) || (r in 2..4 && c in 2..4)
                } else {
                    // Procedural bits derived from data hash
                    val bitIndex = (row * modules + col) % 31
                    val mask = (row + col) % 2 == 0
                    ((hash shr bitIndex) and 1 == 1) xor mask
                }

                if (isFilled) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(col * moduleSize, row * moduleSize),
                        size = Size(moduleSize, moduleSize)
                    )
                }
            }
        }
    }
}
