package com.example.eudiwemu.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import com.authlete.sd.Disclosure

@Composable
fun ClaimSelectionDialog(
    claims: List<Disclosure>,
    onDismiss: () -> Unit,
    onConfirm: (List<Disclosure>) -> Unit
) {
    val selectedClaims = remember { mutableStateListOf<Disclosure>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Claims to Share") },
        text = {
            Column {
                claims.forEach { claim ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = selectedClaims.contains(claim),
                            onCheckedChange = { isChecked ->
                                if (isChecked) selectedClaims.add(claim) else selectedClaims.remove(claim)
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
