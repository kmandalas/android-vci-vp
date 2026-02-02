package com.example.eudiwemu.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun WiaStatusCard(wiaInfo: Map<String, Any>) {
    val status = wiaInfo["status"] as? String ?: "active"
    val issuer = wiaInfo["issuer"] as? String ?: "unknown"
    val clientId = wiaInfo["client_id"] as? String ?: "unknown"
    val walletProviderName = wiaInfo["wallet_provider_name"] as? String ?: "unknown"
    val expiresAt = wiaInfo["expires_at_date"] as? java.util.Date
    val expiresAtFormatted = expiresAt?.let {
        java.text.SimpleDateFormat("dd MMM yyyy HH:mm", java.util.Locale.getDefault()).format(it)
    } ?: wiaInfo["expires_at"] as? String ?: "unknown"

    val isActive = status.lowercase() == "active"
    val statusColor = if (isActive) Color(0xFF4CAF50) else Color(0xFFFF9800)
    val statusIcon = if (isActive) Icons.Default.CheckCircle else Icons.Default.Warning

    Card(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .fillMaxWidth()
            .height(180.dp),
        elevation = CardDefaults.cardElevation(4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
        ) {
            // Header with status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Wallet Instance Attestation",
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Instance Attestation",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = statusIcon,
                        contentDescription = "Status",
                        modifier = Modifier.size(16.dp),
                        tint = statusColor
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = status.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // WIA details
            WiaDetailRow("Issuer", formatIssuer(issuer))
            WiaDetailRow("Client ID", clientId)
            WiaDetailRow("Provider", walletProviderName)
            WiaDetailRow("Expires", expiresAtFormatted)
        }
    }
}

@Composable
private fun WiaDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun formatIssuer(issuer: String): String {
    // Extract host from URL for cleaner display
    return try {
        val url = java.net.URL(issuer)
        "${url.host}${if (url.port != -1) ":${url.port}" else ""}"
    } catch (e: Exception) {
        issuer
    }
}
