package com.example.eudiwemu.ui

import android.content.Intent
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
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
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
            }
        }
    }

    val credentialList = viewModel.credentialList
    val attestationState = viewModel.attestationState
    val issuanceState = viewModel.issuanceState
    val vpState = viewModel.vpRequestState

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

                // Credential card list
                if (credentialList.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "My Credentials",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
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
