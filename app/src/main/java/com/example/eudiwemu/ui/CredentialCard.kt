package com.example.eudiwemu.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.eudiwemu.config.AppConfig
import com.example.eudiwemu.util.ClaimMetadataResolver

/**
 * Fallback hardcoded labels used when no issuer metadata is available.
 */
private val fallbackClaimLabels = mapOf(
    "family_name" to "Family Name",
    "given_name" to "Given Name",
    "birth_date" to "Birth Date",
    "country_code" to "Country",
    "institution_id" to "Institution ID",
    "institution_name" to "Institution Name",
    "company" to "Company"
)

private val fallbackClaimGroups = mapOf(
    "Credential Holder" to listOf("family_name", "given_name", "birth_date"),
    "Competent Institution" to listOf("country_code", "institution_id", "institution_name")
)

/**
 * Icons for known group names. Metadata doesn't include icons,
 * so these remain convention-based.
 */
private val groupIcons = mapOf(
    "credential_holder" to Icons.Default.Person,
    "competent_institution" to Icons.Default.Place,
    // Fallback display-name keys
    "Credential Holder" to Icons.Default.Person,
    "Competent Institution" to Icons.Default.Place
)

@Composable
fun CredentialCard(
    claims: Map<String, String>,
    credentialDisplayName: String? = null,
    credentialFormat: String? = null,
    resolver: ClaimMetadataResolver? = null,
    onDelete: (() -> Unit)? = null,
    compact: Boolean = false,
    onClick: (() -> Unit)? = null,
    issuedAt: Long? = null,
    expiresAt: Long? = null
) {
    Card(
        modifier = Modifier
            .padding(if (compact) 8.dp else 16.dp)
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        elevation = CardDefaults.cardElevation(if (compact) 4.dp else 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .padding(if (compact) 16.dp else 20.dp)
                    .fillMaxWidth()
            ) {
                // Header with icon
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Image(
                        imageVector = Icons.Default.AccountBox,
                        contentDescription = "Credential Icon",
                        modifier = Modifier
                            .size(if (compact) 40.dp else 48.dp)
                            .clip(CircleShape)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = credentialDisplayName ?: "PDA1 Credential",
                            style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        credentialFormat?.let { format ->
                            Spacer(modifier = Modifier.height(4.dp))
                            val (label, color) = when (format) {
                                AppConfig.FORMAT_MSO_MDOC -> "mDoc" to Color(0xFF6F42C1)
                                else -> "SD-JWT" to Color(0xFF28A745)
                            }
                            Text(
                                text = label,
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .background(color, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                if (compact) {
                    // In compact mode, show issuance and expiration dates
                    if (issuedAt != null || expiresAt != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            issuedAt?.let {
                                Text(
                                    text = "Issued: ${formatEpochDate(it)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            expiresAt?.let {
                                Text(
                                    text = "Expires: ${formatEpochDate(it)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    // Full mode — show all grouped claims
                    Spacer(modifier = Modifier.height(16.dp))

                    if (resolver != null) {
                        val groups = resolver.groupByParent()
                        groups.forEach { (parentName, childMetadata) ->
                            val childKeys = childMetadata.mapNotNull { it.path.lastOrNull() }
                            val hasClaimsInGroup = childKeys.any { claims.containsKey(it) }
                            if (hasClaimsInGroup) {
                                val groupDisplayName = parentName.split("_")
                                    .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                                ClaimGroupSection(
                                    groupName = groupDisplayName,
                                    icon = groupIcons[parentName],
                                    claims = claims,
                                    claimKeys = childKeys,
                                    resolver = resolver
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                        }

                        val groupedKeys = groups.values.flatten()
                            .mapNotNull { it.path.lastOrNull() }.toSet()
                        val remainingClaims = claims.filterKeys { !groupedKeys.contains(it) }
                        if (remainingClaims.isNotEmpty()) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            remainingClaims.forEach { (key, value) ->
                                ClaimRow(
                                    label = resolver.getDisplayNameByClaimName(key),
                                    value = value
                                )
                            }
                        }
                    } else {
                        fallbackClaimGroups.forEach { (groupName, groupClaims) ->
                            val hasClaimsInGroup = groupClaims.any { claims.containsKey(it) }
                            if (hasClaimsInGroup) {
                                ClaimGroupSection(
                                    groupName = groupName,
                                    icon = groupIcons[groupName],
                                    claims = claims,
                                    claimKeys = groupClaims,
                                    resolver = null
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                        }

                        val groupedKeys = fallbackClaimGroups.values.flatten().toSet()
                        val remainingClaims = claims.filterKeys { !groupedKeys.contains(it) }
                        if (remainingClaims.isNotEmpty()) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            remainingClaims.forEach { (key, value) ->
                                ClaimRow(
                                    label = fallbackClaimLabels[key] ?: key,
                                    value = value
                                )
                            }
                        }
                    }
                }
            }

            if (!compact) {
                onDelete?.let { deleteAction ->
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete credential",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .size(24.dp)
                            .clickable { deleteAction() }
                    )
                }
            }
        }
    }
}

private fun formatEpochDate(epochSeconds: Long): String {
    val instant = java.time.Instant.ofEpochSecond(epochSeconds)
    val date = instant.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    return java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy").format(date)
}

@Composable
private fun ClaimGroupSection(
    groupName: String,
    icon: ImageVector?,
    claims: Map<String, String>,
    claimKeys: List<String>,
    resolver: ClaimMetadataResolver?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Group header
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon?.let {
                    Icon(
                        imageVector = it,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = groupName,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            // Claims in this group
            claimKeys.forEach { key ->
                claims[key]?.let { value ->
                    val label = if (resolver != null) {
                        resolver.getDisplayNameByClaimName(key)
                    } else {
                        fallbackClaimLabels[key] ?: key
                    }
                    ClaimRow(
                        label = label,
                        value = value
                    )
                }
            }
        }
    }
}

@Composable
private fun ClaimRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(0.6f)
        )
    }
}
