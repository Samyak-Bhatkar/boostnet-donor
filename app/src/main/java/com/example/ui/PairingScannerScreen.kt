package com.example.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.crypto.CryptoManager
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

@Composable
fun PairingScannerScreen(
    onDismiss: () -> Unit,
    onPairedSuccess: (String, String) -> Unit
) {
    BackHandler { onDismiss() }
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    var manualCodeInput by remember { mutableStateOf("") }
    var isManualMode by remember { mutableStateOf(false) }
    var pairedCode by remember { mutableStateOf<String?>(null) }
    var verificationToken by remember { mutableStateOf<String?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("pairing_scanner_screen"),
        color = Color(0xFF0F1115)
    ) {
        if (pairedCode != null) {
            // Success Screen
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Paired",
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(72.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Laptop Paired Successfully!",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Verify this confirmation code matches your laptop screen:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFAAAAAA),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E232D)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = verificationToken ?: "OK",
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.headlineLarge,
                        color = Color(0xFF4285F4),
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 4.sp
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = {
                        onPairedSuccess(pairedCode!!, verificationToken ?: "")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4285F4)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .testTag("confirm_pair_button")
                ) {
                    Text("Return to Dashboard", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            // Scanner Screen
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("scanner_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                    Text(
                        text = "Scan Laptop QR",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { isManualMode = !isManualMode },
                        modifier = Modifier.testTag("toggle_manual_mode_button")
                    ) {
                        Text(
                            text = if (isManualMode) "Camera" else "Manual PIN",
                            color = Color(0xFF4285F4),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (isManualMode) {
                    // Manual PIN / Secret Input
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            tint = Color(0xFF4285F4),
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Enter 6-Digit PIN or Secret",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Enter the pairing code displayed in the BoostNet Desktop app on your laptop.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFAAAAAA),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(20.dp))

                        OutlinedTextField(
                            value = manualCodeInput,
                            onValueChange = { manualCodeInput = it },
                            label = { Text("6-Digit Code / Secret") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("manual_pair_input")
                        )

                        if (scanError != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = scanError!!,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = {
                                val secret = CryptoManager.parseLaptopQrPayload(manualCodeInput)
                                if (secret != null) {
                                    CryptoManager.savePairedSecret(context, secret)
                                    val token = CryptoManager.getVisualVerificationCode(secret)
                                    pairedCode = secret
                                    verificationToken = token
                                } else {
                                    scanError = "Invalid code. Please check laptop screen."
                                }
                            },
                            enabled = manualCodeInput.isNotBlank(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("submit_manual_pair_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4285F4))
                        ) {
                            Text("Pair With Laptop", fontWeight = FontWeight.Bold)
                        }
                    }
                } else if (!hasCameraPermission) {
                    // Permission Request Fallback
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Camera Permission Required",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "BoostNet needs camera access to scan the QR code shown on your laptop screen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFAAAAAA),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4285F4))
                        ) {
                            Text("Grant Camera Permission")
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        TextButton(onClick = { isManualMode = true }) {
                            Text("Enter PIN Manually", color = Color(0xFF4285F4))
                        }
                    }
                } else {
                    // Live Camera Scanner
                    Box(modifier = Modifier.fillMaxSize()) {
                        CameraPreviewView(
                            onQrCodeScanned = { rawPayload ->
                                val secret = CryptoManager.parseLaptopQrPayload(rawPayload)
                                if (secret != null && pairedCode == null) {
                                    CryptoManager.savePairedSecret(context, secret)
                                    val token = CryptoManager.getVisualVerificationCode(secret)
                                    pairedCode = secret
                                    verificationToken = token
                                }
                            }
                        )

                        // Viewfinder Overlay
                        ViewfinderOverlay(modifier = Modifier.fillMaxSize())

                        // Instructions at bottom
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 40.dp, start = 24.dp, end = 24.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xCC000000))
                                .padding(horizontal = 20.dp, vertical = 12.dp)
                        ) {
                            Text(
                                text = "Point camera at the QR code on your laptop",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("UnsafeOptInUsageError")
@Composable
private fun CameraPreviewView(
    onQrCodeScanned: (String) -> Unit
) {
    val context = LocalContext.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val barcodeScanner = remember { BarcodeScanning.getClient() }
    var isProcessing by remember { mutableStateOf(false) }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    processImageProxy(barcodeScanner, imageProxy) { qrValue ->
                        if (!isProcessing) {
                            isProcessing = true
                            onQrCodeScanned(qrValue)
                        }
                    }
                }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        ctx as androidx.lifecycle.LifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}

@SuppressLint("UnsafeOptInUsageError")
private fun processImageProxy(
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    imageProxy: ImageProxy,
    onSuccess: (String) -> Unit
) {
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    if (barcode.valueType == Barcode.TYPE_TEXT || barcode.format == Barcode.FORMAT_QR_CODE) {
                        barcode.rawValue?.let { raw ->
                            onSuccess(raw)
                        }
                    }
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    } else {
        imageProxy.close()
    }
}

@Composable
private fun ViewfinderOverlay(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "laser_transition")
    val laserY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "laser_y"
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(260.dp)
                .border(2.dp, Color(0xFF4285F4), RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val y = size.height * laserY
                drawLine(
                    color = Color(0xFF00E5FF),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 3.dp.toPx()
                )
            }
        }
    }
}
