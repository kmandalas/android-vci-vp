package com.example.eudiwemu.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavController
import com.example.eudiwemu.QrScannerActivity
import com.example.eudiwemu.config.AppConfig
import com.example.eudiwemu.security.SecurityPostureLevel
import com.example.eudiwemu.ui.viewmodel.WalletEvent
import com.example.eudiwemu.ui.viewmodel.WalletViewModel

@Composable
fun WalletScreen(
    activity: FragmentActivity,
    intent: Intent?,
    viewModel: WalletViewModel,
    navController: NavController
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }

    // Initialize on first composition
    LaunchedEffect(Unit) {
        viewModel.initialize(activity)
    }

    // Handle deep links
    LaunchedEffect(intent) {
        if (intent?.data != null) {
            viewModel.handleDeepLink(intent, activity)
            // Clear the intent so it's not re-processed on recomposition
            (activity as? com.example.eudiwemu.MainActivity)?.currentIntent?.value = null
        }
    }

    // Collect one-shot events
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is WalletEvent.ShowSnackbar -> { viewModel.showBanner(event.message) }
                is WalletEvent.OpenBrowser -> {
                    val browseIntent = Intent(Intent.ACTION_VIEW, event.uri.toUri())
                    context.startActivity(browseIntent)
                }
                is WalletEvent.NavigateToDetail -> {
                    viewModel.selectCredential(event.credentialKey)
                    navController.navigate("credential_detail/${event.credentialKey}")
                }
                is WalletEvent.NavigateToTransactionLog -> {
                    navController.navigate("transaction_log")
                }
                is WalletEvent.ExportReady,
                is WalletEvent.ImportComplete -> { /* handled by TransactionLogScreen */ }
            }
        }
    }

    val credentialList = viewModel.credentialList
    val attestationState = viewModel.attestationState
    val issuanceState = viewModel.issuanceState
    val vpState = viewModel.vpRequestState

    if (viewModel.isInitializing) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        floatingActionButton = {
            if (credentialList.isNotEmpty()) {
                val pulse = rememberInfiniteTransition(label = "fab-pulse")
                val scale by pulse.animateFloat(
                    initialValue = 1f,
                    targetValue = 1.08f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "fab-scale"
                )
                FloatingActionButton(
                    onClick = {
                        val qrIntent = Intent(context, QrScannerActivity::class.java)
                        activity.startActivity(qrIntent)
                    },
                    modifier = Modifier.graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan QR Code")
                }
            }
        }
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

                AttestationCarousel(
                    wiaInfo = attestationState.wiaInfo,
                    wuaInfo = attestationState.wuaInfo,
                    postureState = viewModel.postureState
                )

                // Inline posture warning for Level 2-3
                val posture = viewModel.postureState
                if (posture.level != null &&
                    posture.level != com.example.eudiwemu.security.SecurityPostureLevel.LEVEL_1 &&
                    posture.level != com.example.eudiwemu.security.SecurityPostureLevel.LEVEL_4
                ) {
                    val bgColor = if (posture.level == com.example.eudiwemu.security.SecurityPostureLevel.LEVEL_3)
                        androidx.compose.ui.graphics.Color(0xFFFF9800) else androidx.compose.ui.graphics.Color(0xFFFFEB3B)
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .padding(vertical = 4.dp),
                        color = bgColor.copy(alpha = 0.15f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                        tonalElevation = 0.dp
                    ) {
                        Text(
                            text = "⚠️ ${posture.findings.joinToString(" · ")}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            color = if (posture.level == com.example.eudiwemu.security.SecurityPostureLevel.LEVEL_3)
                                androidx.compose.ui.graphics.Color(0xFFE65100)
                            else androidx.compose.ui.graphics.Color(0xFF5D4037)
                        )
                    }
                }

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
                        // Conformance test entry (always available)
                        DropdownMenuItem(
                            text = { Text(AppConfig.CONFORMANCE_TEST_LABEL) },
                            onClick = {
                                viewModel.updateSelectedCredentialType(
                                    AppConfig.CONFORMANCE_TEST_LABEL,
                                    AppConfig.CONFORMANCE_TEST_VALUE
                                )
                                expanded = false
                            }
                        )
                    }
                }

                // Show issuer URL field when conformance test is selected
                if (issuanceState.isConformanceTest) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = issuanceState.conformanceIssuerUrl,
                        onValueChange = { viewModel.updateConformanceIssuerUrl(it) },
                        label = { Text("Credential Issuer URL") },
                        placeholder = { Text("https://www.certification.openid.net/test/a/...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(0.8f)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (issuanceState.isLoading) {
                    CircularProgressIndicator()
                } else {
                    Button(
                        onClick = {
                            if (issuanceState.isConformanceTest) {
                                viewModel.requestConformanceCredential(activity)
                            } else {
                                viewModel.requestCredential(activity)
                            }
                        },
                        enabled = issuanceState.selectedValue.isNotEmpty() &&
                                (!issuanceState.isConformanceTest || issuanceState.conformanceIssuerUrl.isNotBlank())
                    ) {
                        Text("Request VC")
                    }
                }

                // Credential card list
                if (credentialList.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "My Credentials",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { navController.navigate("transaction_log") },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.History,
                                contentDescription = "Transaction Log",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    credentialList.forEach { cred ->
                        cred.claims?.let { claims ->
                            CredentialCard(
                                claims = viewModel.flattenClaimsForDisplay(claims),
                                credentialDisplayName = cred.credentialDisplayName,
                                credentialFormat = cred.credentialFormat,
                                resolver = cred.claimResolver,
                                compact = true,
                                issuedAt = cred.issuedAt,
                                expiresAt = cred.expiresAt,
                                onClick = {
                                    viewModel.selectCredential(cred.credentialKey)
                                    navController.navigate("credential_detail/${cred.credentialKey}")
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(88.dp))
            }
        }
    }
    TopBanner(viewModel = viewModel, modifier = Modifier.align(Alignment.TopCenter))
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

    // Level 4 posture blocking dialog
    if (viewModel.postureState.level == SecurityPostureLevel.LEVEL_4) {
        AlertDialog(
            onDismissRequest = { /* non-dismissable */ },
            title = { Text("Critical Security Alert") },
            text = {
                Column {
                    Text("Your device has critical security issues that prevent credential operations:")
                    Spacer(modifier = Modifier.height(8.dp))
                    viewModel.postureState.findings.forEach { finding ->
                        Text("• $finding", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { }
        )
    }
}

@Composable
fun TopBanner(viewModel: WalletViewModel, modifier: Modifier = Modifier) {
    val message = viewModel.bannerMessage

    LaunchedEffect(message) {
        if (message != null) {
            delay(3000)
            viewModel.clearBanner()
        }
    }

    AnimatedVisibility(
        visible = message != null,
        modifier = modifier,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut()
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.inverseSurface,
            shape = RoundedCornerShape(12.dp),
            shadowElevation = 6.dp
        ) {
            Text(
                text = message ?: "",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                color = MaterialTheme.colorScheme.inverseOnSurface,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
