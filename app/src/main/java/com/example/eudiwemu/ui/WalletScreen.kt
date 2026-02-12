package com.example.eudiwemu.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import com.example.eudiwemu.QrScannerActivity
import com.example.eudiwemu.config.AppConfig
import com.example.eudiwemu.ui.viewmodel.WalletEvent
import com.example.eudiwemu.ui.viewmodel.WalletViewModel

@Composable
fun WalletScreen(
    activity: FragmentActivity,
    intent: Intent?,
    viewModel: WalletViewModel
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var expanded by remember { mutableStateOf(false) }

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

    // Initialize on first composition
    LaunchedEffect(Unit) {
        viewModel.initialize(activity)
    }

    // Handle deep links
    LaunchedEffect(intent) {
        viewModel.handleDeepLink(intent, activity)
    }

    // Collect one-shot events
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is WalletEvent.ShowSnackbar -> snackbarHostState.showSnackbar(event.message)
                is WalletEvent.OpenBrowser -> {
                    val browseIntent = Intent(Intent.ACTION_VIEW, event.uri.toUri())
                    context.startActivity(browseIntent)
                }
            }
        }
    }

    val credentialState = viewModel.credentialState
    val attestationState = viewModel.attestationState
    val issuanceState = viewModel.issuanceState
    val vpState = viewModel.vpRequestState
    val proximityState = viewModel.proximityState

    // Show proximity presentation screen when active
    if (proximityState.isActive) {
        ProximityPresentationScreen(
            proximityState = proximityState,
            onSubmitResponse = { selected -> viewModel.submitProximityResponse(selected) },
            onStop = { viewModel.stopProximityPresentation() }
        )
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                AttestationCarousel(wiaInfo = attestationState.wiaInfo, wuaInfo = attestationState.wuaInfo)

                Spacer(modifier = Modifier.height(8.dp))

                // Dropdown Menu
                Box {
                    OutlinedTextField(
                        value = issuanceState.selectedLabel,
                        onValueChange = {},
                        label = { Text("Select VC Type") },
                        readOnly = true,
                        trailingIcon = {
                            Icon(
                                Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                modifier = Modifier.clickable { expanded = true }
                            )
                        },
                        modifier = Modifier
                            .clickable { expanded = true }
                            .fillMaxWidth(0.8f)
                    )

                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        issuanceState.credentialTypes.forEach { (label, value) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    viewModel.updateSelectedCredentialType(label, value)
                                    expanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (issuanceState.isLoading) {
                    CircularProgressIndicator()
                } else {
                    Button(
                        onClick = { viewModel.requestCredential(activity) },
                        enabled = issuanceState.selectedValue.isNotEmpty()
                    ) {
                        Text("Request VC")
                    }
                }

                credentialState.claims?.let { claims ->
                    CredentialCard(
                        claims = viewModel.flattenClaimsForDisplay(claims),
                        credentialDisplayName = credentialState.credentialDisplayName,
                        credentialFormat = viewModel.getStoredCredentialFormat(),
                        resolver = credentialState.claimResolver,
                        onDelete = { viewModel.deleteCredential() }
                    )
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
                    if (viewModel.getStoredCredentialFormat() == AppConfig.FORMAT_MSO_MDOC) {
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
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // SD-JWT claim selection dialog
    vpState.selectedClaims?.let { claims ->
        ClaimSelectionDialog(
            claims = claims,
            resolver = vpState.vpClaimResolver,
            clientName = vpState.clientName,
            logoUri = vpState.logoUri,
            purpose = vpState.purpose,
            onDismiss = { viewModel.dismissSdJwtDialog() },
            onConfirm = { selected -> viewModel.submitSdJwtVpToken(selected) }
        )
    }

    // mDoc claim selection dialog
    vpState.mdocAvailableClaims?.let { claimNames ->
        MDocClaimSelectionDialog(
            claimNames = claimNames,
            clientName = vpState.clientName,
            logoUri = vpState.logoUri,
            purpose = vpState.purpose,
            onDismiss = { viewModel.dismissMDocDialog() },
            onConfirm = { selectedNames -> viewModel.submitMDocVpToken(selectedNames) }
        )
    }
}
