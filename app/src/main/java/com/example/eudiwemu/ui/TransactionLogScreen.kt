package com.example.eudiwemu.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.example.eudiwemu.data.entity.TransactionLogEntry
import com.example.eudiwemu.ui.viewmodel.WalletEvent
import com.example.eudiwemu.ui.viewmodel.WalletViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionLogScreen(
    viewModel: WalletViewModel,
    activity: FragmentActivity,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var showExportDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf("") }
    var importPassword by remember { mutableStateOf("") }
    var pendingExportData by remember { mutableStateOf<ByteArray?>(null) }
    var pendingImportData by remember { mutableStateOf<ByteArray?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null && pendingExportData != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(pendingExportData) }
                Toast.makeText(context, "✅ Export saved", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "❌ Failed to write file: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            pendingExportData = null
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null) {
                    pendingImportData = bytes
                    showImportDialog = true
                }
            } catch (e: Exception) {
                Toast.makeText(context, "❌ Failed to read file: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadTransactionLog()
    }

    // Collect export events
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is WalletEvent.ExportReady -> {
                    pendingExportData = event.data
                    exportLauncher.launch("kwallet_backup.kwallet")
                }
                is WalletEvent.ImportComplete -> {
                    val hints = event.credentialHints
                    val msg = buildString {
                        append("✅ Imported ${event.logCount} log entries")
                        if (hints.isNotEmpty()) {
                            append("\n📋 Previously held credentials:")
                            hints.forEach { h ->
                                append("\n  • ${h.displayName ?: h.credentialKey} (${h.format})")
                            }
                        }
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
                is WalletEvent.ShowSnackbar -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
                else -> {}
            }
        }
    }

    val logEntries = viewModel.transactionLog

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transaction Log") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (logEntries.isEmpty()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "No transactions yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item { Spacer(modifier = Modifier.height(8.dp)) }
                    items(logEntries) { entry ->
                        TransactionLogCard(entry)
                    }
                    item { Spacer(modifier = Modifier.height(8.dp)) }
                }
            }

            // Export/Import buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { showExportDialog = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Export")
                }
                Button(
                    onClick = { importLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Import")
                }
            }
        }
    }

    // Export password dialog
    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false; exportPassword = "" },
            title = { Text("Export Wallet Data") },
            text = {
                Column {
                    Text("Enter a password to encrypt the export file.")
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = exportPassword,
                        onValueChange = { exportPassword = it },
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (exportPassword.isNotEmpty()) {
                            viewModel.startExport(exportPassword, activity)
                            showExportDialog = false
                            exportPassword = ""
                        }
                    },
                    enabled = exportPassword.isNotEmpty()
                ) {
                    Text("Export")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false; exportPassword = "" }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Import password dialog
    if (showImportDialog && pendingImportData != null) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false; importPassword = ""; pendingImportData = null },
            title = { Text("Import Wallet Data") },
            text = {
                Column {
                    Text("Enter the password used when exporting.")
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = importPassword,
                        onValueChange = { importPassword = it },
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (importPassword.isNotEmpty()) {
                            viewModel.processImport(pendingImportData!!, importPassword)
                            showImportDialog = false
                            importPassword = ""
                            pendingImportData = null
                        }
                    },
                    enabled = importPassword.isNotEmpty()
                ) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false; importPassword = ""; pendingImportData = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun TransactionLogCard(entry: TransactionLogEntry) {
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = entry.typeIcon(),
                contentDescription = null,
                tint = entry.typeColor(),
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.credentialDisplayName ?: entry.credentialType ?: entry.transactionType,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = entry.transactionType.replace("_", " "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!entry.counterpartyName.isNullOrEmpty()) {
                    Text(
                        text = "To: ${entry.counterpartyName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = dateFormat.format(Date(entry.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Text(
                text = if (entry.status == "SUCCESS") "✅" else "❌",
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

@Composable
private fun TransactionLogEntry.typeIcon(): ImageVector = when (transactionType) {
    TransactionLogEntry.TYPE_ISSUANCE -> Icons.Default.Description
    TransactionLogEntry.TYPE_PRESENTATION -> Icons.Default.Share
    TransactionLogEntry.TYPE_PROXIMITY_PRESENTATION -> Icons.Default.Nfc
    TransactionLogEntry.TYPE_DELETION -> Icons.Default.Delete
    else -> Icons.Default.Description
}

@Composable
private fun TransactionLogEntry.typeColor() = when (transactionType) {
    TransactionLogEntry.TYPE_ISSUANCE -> MaterialTheme.colorScheme.primary
    TransactionLogEntry.TYPE_PRESENTATION -> MaterialTheme.colorScheme.secondary
    TransactionLogEntry.TYPE_PROXIMITY_PRESENTATION -> MaterialTheme.colorScheme.tertiary
    TransactionLogEntry.TYPE_DELETION -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurface
}
