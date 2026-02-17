package com.example.eudiwemu.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.example.eudiwemu.ui.viewmodel.ProximityState
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Full-screen composable for ISO 18013-5 proximity (QR/BLE) presentation.
 *
 * States:
 * 1. QR display → "Scan this QR code with verifier device"
 * 2. "Connecting..." / "Connected"
 * 3. Claim selection dialog (when DeviceRequest received)
 * 4. "Sending..." → "Presentation complete"
 * 5. Back to wallet via dismiss
 */
@Composable
fun ProximityPresentationScreen(
    proximityState: ProximityState,
    onSubmitResponse: (List<String>) -> Unit,
    onStop: () -> Unit
) {
    // Clean up on dispose
    DisposableEffect(Unit) {
        onDispose { /* cleanup handled by onStop button */ }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Present in Person",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        // QR Code display
        val qrContent = proximityState.qrContent
        if (qrContent != null && proximityState.requestedClaims == null
            && proximityState.status != "Presentation complete") {
            val qrBitmap = remember(qrContent) { generateQrBitmap(qrContent, 300) }
            if (qrBitmap != null) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "Device Engagement QR Code",
                    modifier = Modifier.size(300.dp)
                )
            }
        } else if (qrContent == null && proximityState.status != "Presentation complete") {
            CircularProgressIndicator()
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Status text
        Text(
            text = proximityState.status,
            style = MaterialTheme.typography.bodyLarge,
            color = if (proximityState.status.startsWith("Error"))
                MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Cancel / Done button
        if (proximityState.status == "Presentation complete" ||
            proximityState.status == "Disconnected") {
            Button(onClick = onStop) {
                Text("Done")
            }
        } else {
            OutlinedButton(onClick = onStop) {
                Text("Cancel")
            }
        }
    }

    // Claim selection dialog when DeviceRequest is received
    proximityState.requestedClaims?.let { claims ->
        ProximityClaimSelectionDialog(
            claimNames = claims,
            onConfirm = { selected -> onSubmitResponse(selected) },
            onDismiss = onStop
        )
    }
}

/**
 * Claim selection dialog for proximity presentation.
 * Simplified version without verifier branding (proximity has no client_metadata).
 */
@Composable
private fun ProximityClaimSelectionDialog(
    claimNames: List<String>,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val selectedNames = remember { mutableStateListOf<String>().apply { addAll(claimNames) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Verifier Request", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "A nearby device is requesting the following claims",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text("Select Claims to Share", style = MaterialTheme.typography.bodyLarge)
            }
        },
        text = {
            Column {
                claimNames.forEach { claimName ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = selectedNames.contains(claimName),
                            onCheckedChange = { isChecked ->
                                if (isChecked) selectedNames.add(claimName)
                                else selectedNames.remove(claimName)
                            }
                        )
                        Column(modifier = Modifier.padding(start = 4.dp)) {
                            Text(
                                text = formatClaimLabel(claimName),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selectedNames.toList()) },
                enabled = selectedNames.isNotEmpty()
            ) { Text("Share") }
        },
        dismissButton = {
            Button(onClick = onDismiss) { Text("Deny") }
        }
    )
}

/** Convert snake_case element identifier to Title Case label. */
private fun formatClaimLabel(claimName: String): String {
    return claimName.replace("_", " ")
        .split(" ")
        .joinToString(" ") { word ->
            word.replaceFirstChar { it.uppercase() }
        }
}

/** Generate a QR code bitmap from content string using ZXing. */
private fun generateQrBitmap(content: String, size: Int): Bitmap? {
    return try {
        val writer = QRCodeWriter()
        val bitMatrix: BitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
}
