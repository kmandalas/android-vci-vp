package com.example.eudiwemu.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.authlete.sd.Disclosure

/**
 * Human-readable labels for parent claim categories.
 */
private val parentClaimLabels = mapOf(
    "credential_holder" to "Credential Holder (name, birth date)",
    "competent_institution" to "Competent Institution (country, ID, name)"
)

/**
 * Parent claim names that should be shown as selectable options.
 */
private val parentClaimNames = setOf("credential_holder", "competent_institution")

/**
 * Mapping of parent claims to their nested child claim names.
 * When a parent is selected, all its children must be included in the VP.
 */
private val parentToChildren = mapOf(
    "credential_holder" to setOf("family_name", "given_name", "birth_date"),
    "competent_institution" to setOf("country_code", "institution_id", "institution_name")
)

@Composable
fun ClaimSelectionDialog(
    claims: List<Disclosure>,
    clientName: String,
    logoUri: String,
    purpose: String,
    onDismiss: () -> Unit,
    onConfirm: (List<Disclosure>) -> Unit
) {
    val selectedClaims = remember { mutableStateListOf<Disclosure>() }

    // Filter to show only parent claims (credential_holder, competent_institution)
    val parentClaims = claims.filter { parentClaimNames.contains(it.claimName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Show logo using Coil
                AsyncImage(
                    model = logoUri,
                    contentDescription = "Client Logo",
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = clientName, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = purpose,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text("Select Claims to Share", style = MaterialTheme.typography.bodyLarge)
            }
        },
        text = {
            Column {
                parentClaims.forEach { claim ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = selectedClaims.contains(claim),
                            onCheckedChange = { isChecked ->
                                if (isChecked) selectedClaims.add(claim)
                                else selectedClaims.remove(claim)
                            }
                        )
                        Column(modifier = Modifier.padding(start = 4.dp)) {
                            Text(
                                text = parentClaimLabels[claim.claimName] ?: claim.claimName,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                // Expand selected parent disclosures to include all their children
                val selectedParentNames = selectedClaims.mapNotNull { it.claimName }.toSet()
                val childNamesToInclude = selectedParentNames
                    .flatMap { parentToChildren[it] ?: emptySet() }
                    .toSet()

                // Include both selected parents AND all their children from the full claims list
                val expandedSelection = claims.filter { disclosure ->
                    selectedParentNames.contains(disclosure.claimName) ||
                    childNamesToInclude.contains(disclosure.claimName)
                }

                onConfirm(expandedSelection)
            }) { Text("Confirm") }
        },
        dismissButton = {
            Button(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

