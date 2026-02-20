package com.example.eudiwemu.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.example.eudiwemu.QrScannerActivity
import com.example.eudiwemu.config.AppConfig
import com.example.eudiwemu.ui.viewmodel.WalletViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialDetailScreen(
    credentialKey: String,
    viewModel: WalletViewModel,
    activity: FragmentActivity,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val credential = viewModel.credentialList.find { it.credentialKey == credentialKey }
    val proximityState = viewModel.proximityState

    // BLE permissions required for proximity presentation (Android 12+)
    val blePermissions = arrayOf(
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN
    )

    val blePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            viewModel.startProximityPresentation(activity)
        }
    }

    // Show proximity presentation screen when active
    if (proximityState.isActive) {
        ProximityPresentationScreen(
            proximityState = proximityState,
            onSubmitResponse = { selected -> viewModel.submitProximityResponse(selected) },
            onStop = { viewModel.stopProximityPresentation() }
        )
        return
    }

    // Navigate back when credential no longer exists (e.g., after deletion)
    LaunchedEffect(credential) {
        if (credential == null) onBack()
    }
    if (credential == null) return

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(credential.credentialDisplayName ?: "Credential") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            credential.claims?.let { claims ->
                CredentialCard(
                    claims = viewModel.flattenClaimsForDisplay(claims),
                    credentialDisplayName = credential.credentialDisplayName,
                    credentialFormat = credential.credentialFormat,
                    resolver = credential.claimResolver,
                    onDelete = {
                        viewModel.deleteCredential(credentialKey)
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    val qrIntent = Intent(context, QrScannerActivity::class.java)
                    activity.startActivity(qrIntent)
                }
            ) {
                Text("Scan QR")
            }

            // Show "Present in Person" button only for mDoc credentials
            if (credential.credentialFormat == AppConfig.FORMAT_MSO_MDOC) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        val allGranted = blePermissions.all { perm ->
                            context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
                        }
                        if (allGranted) {
                            viewModel.startProximityPresentation(activity)
                        } else {
                            blePermissionLauncher.launch(blePermissions)
                        }
                    }
                ) {
                    Text("Present in Person")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
