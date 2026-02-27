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
import androidx.compose.material.icons.filled.Lock
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
fun WuaStatusCard(wuaInfo: Map<String, Any>) {
    val status = wuaInfo["status"] as? String ?: "active"
    val wscdType = wuaInfo["wscd_type"] as? String ?: "unknown"
    val securityLevel = wuaInfo["security_level"] as? String ?: "unknown"
    val wuaId = wuaInfo["wua_id"] as? String ?: "unknown"
    val expiresAt = wuaInfo["expires_at_date"] as? java.util.Date
    val expiresAtFormatted = expiresAt?.let {
        java.text.SimpleDateFormat("dd MMM yyyy HH:mm", java.util.Locale.getDefault()).format(it)
    } ?: wuaInfo["expires_at"] as? String ?: "unknown"

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
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Wallet Attestation",
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Unit Attestation",
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

            // Security details
            WuaDetailRow("Security Level", formatSecurityLevel(securityLevel))
            WuaDetailRow("WSCD Type", formatWscdType(wscdType))
            WuaDetailRow("Expires", expiresAtFormatted)
            WuaDetailRow("WUA ID", if (wuaId.length > 8) wuaId.take(8) + "..." else wuaId)
        }
    }
}

@Composable
private fun WuaDetailRow(label: String, value: String) {
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

private fun formatWscdType(wscdType: String): String {
    return when (wscdType.lowercase()) {
        "strongbox" -> "StrongBox (Hardware)"
        "tee" -> "TEE (Trusted Execution)"
        "software" -> "Software"
        "iso_18045_high" -> "StrongBox (Hardware)"
        "iso_18045_moderate", "iso_18045_enhanced-basic" -> "TEE (Trusted Execution)"
        "iso_18045_basic" -> "Software"
        else -> wscdType.replaceFirstChar { it.uppercase() }
    }
}

private fun formatSecurityLevel(securityLevel: String): String {
    return when (securityLevel.lowercase()) {
        "strongbox" -> "StrongBox (High)"
        "trustedenvironment", "tee" -> "TEE (High)"
        "software" -> "Software (Basic)"
        "iso_18045_high" -> "ISO 18045 High"
        "iso_18045_moderate" -> "ISO 18045 Moderate"
        "iso_18045_enhanced-basic" -> "ISO 18045 Enhanced-Basic"
        "iso_18045_basic" -> "ISO 18045 Basic"
        else -> securityLevel.replaceFirstChar { it.uppercase() }
    }
}
