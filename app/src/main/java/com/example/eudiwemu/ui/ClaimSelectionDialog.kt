package com.example.eudiwemu.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
                claims.forEach { claim ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = selectedClaims.contains(claim),
                            onCheckedChange = { isChecked ->
                                if (isChecked) selectedClaims.add(claim)
                                else selectedClaims.remove(claim)
                            }
                        )
                        Text(text = claim.claimName)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selectedClaims) }) { Text("Confirm") }
        },
        dismissButton = {
            Button(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

