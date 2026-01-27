package com.example.eudiwemu.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Human-readable labels for claim fields.
 */
private val claimLabels = mapOf(
    "family_name" to "Family Name",
    "given_name" to "Given Name",
    "birth_date" to "Birth Date",
    "country_code" to "Country",
    "institution_id" to "Institution ID",
    "institution_name" to "Institution Name",
    "company" to "Company"
)

/**
 * Grouping of nested claims under their parent categories.
 */
private val claimGroups = mapOf(
    "Credential Holder" to listOf("family_name", "given_name", "birth_date"),
    "Competent Institution" to listOf("country_code", "institution_id", "institution_name")
)

/**
 * Icons for each group.
 */
private val groupIcons = mapOf(
    "Credential Holder" to Icons.Default.Person,
    "Competent Institution" to Icons.Default.Place
)

@Composable
fun CredentialCard(
    claims: Map<String, String>,
    onDelete: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxWidth(),
        elevation = CardDefaults.cardElevation(8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
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
                            .size(48.dp)
                            .clip(CircleShape)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "PDA1 Credential",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Display grouped claims
                claimGroups.forEach { (groupName, groupClaims) ->
                    val hasClaimsInGroup = groupClaims.any { claims.containsKey(it) }
                    if (hasClaimsInGroup) {
                        ClaimGroupSection(
                            groupName = groupName,
                            icon = groupIcons[groupName],
                            claims = claims,
                            claimKeys = groupClaims
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }

                // Display any remaining claims not in groups
                val groupedKeys = claimGroups.values.flatten().toSet()
                val remainingClaims = claims.filterKeys { !groupedKeys.contains(it) }
                if (remainingClaims.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    remainingClaims.forEach { (key, value) ->
                        ClaimRow(
                            label = claimLabels[key] ?: key,
                            value = value
                        )
                    }
                }
            }

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

@Composable
private fun ClaimGroupSection(
    groupName: String,
    icon: ImageVector?,
    claims: Map<String, String>,
    claimKeys: List<String>
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
                    ClaimRow(
                        label = claimLabels[key] ?: key,
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

