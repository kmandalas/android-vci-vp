package com.example.eudiwemu.ui

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.authlete.sd.Disclosure
import com.example.eudiwemu.handleDeepLink
import com.example.eudiwemu.requestVC
import com.example.eudiwemu.security.getEncryptedPrefs
import com.example.eudiwemu.service.IssuanceService
import com.example.eudiwemu.service.VpTokenService
import com.example.eudiwemu.submitVpToken
import kotlinx.coroutines.launch

@Composable
fun WalletScreen(
    activity: FragmentActivity,
    context: Context,
    intent: Intent?,
    issuanceService: IssuanceService,
    vpTokenService: VpTokenService
) {
    val clientId = remember { mutableStateOf("") }
    val requestUri = remember { mutableStateOf("") }
    val responseUri = remember { mutableStateOf("") }
    var credentialClaims by remember { mutableStateOf<Map<String, String>?>(null) }
    val selectedClaims = remember { mutableStateOf<List<Disclosure>?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    // Load stored credential on launch
    LaunchedEffect(Unit) {
        try {
            val prefs = getEncryptedPrefs(context, activity)
            val storedCredential = prefs.getString("stored_vc", null)

            if (!storedCredential.isNullOrEmpty()) {
                try {
                    credentialClaims = issuanceService.decodeCredential(storedCredential)
                } catch (e: Exception) {
                    Log.e("WalletApp", "Error decoding stored VC", e)
                }
            }
        } catch (e: Exception) {
            Log.e("WalletApp", "Error fetching encrypted prefs: $e")
        }
    }

    // Handle deep links
    LaunchedEffect(intent) {
        handleDeepLink(context, activity, intent, clientId, requestUri, vpTokenService, selectedClaims, responseUri)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(modifier = Modifier.height(16.dp))

                if (isLoading) {
                    CircularProgressIndicator()
                } else {
                    Button(onClick = {
                        // Set isLoading to true as soon as the button is clicked
                        isLoading = true

                        // Start a coroutine to call the suspend function requestVC
                        coroutineScope.launch {
                            try {
                                // Call the suspend function requestVC
                                val result = requestVC(activity, context, issuanceService)

                                // Set isLoading to false immediately after receiving the result
                                isLoading = false

                                // Check if the result was successful or failed
                                if (result.isSuccess) {
                                    val (message, claims) = result.getOrNull()!!

                                    // Update credentialClaims immediately
                                    credentialClaims = claims

                                    // Show success message in snackbar
                                    snackbarHostState.showSnackbar(
                                        message = message,
                                        actionLabel = "Dismiss",
                                        duration = SnackbarDuration.Short
                                    )
                                } else {
                                    // Show failure message in snackbar
                                    snackbarHostState.showSnackbar(
                                        message = "❌ Error: ${result.exceptionOrNull()?.message}",
                                        actionLabel = "Dismiss",
                                        duration = SnackbarDuration.Long
                                    )
                                }
                            } catch (e: Exception) {
                                Log.e("WalletApp", "Error requesting VC", e)
                                // Set isLoading to false in case of exception
                                isLoading = false
                                // Show generic error message in snackbar
                                snackbarHostState.showSnackbar(
                                    message = "❌ Error requesting VC: ${e.message}",
                                    actionLabel = "Dismiss",
                                    duration = SnackbarDuration.Long
                                )
                            }
                        }
                    }) {
                        Text("Request VC")
                    }
                }

                // Show the CredentialCard immediately after claims are fetched
                credentialClaims?.let {
                    CredentialCard(it)
                }
            }
        }
    }

    // Show claim selection dialog when claims are available
    selectedClaims.value?.let { claims ->
        ClaimSelectionDialog(
            claims = claims,
            onDismiss = { selectedClaims.value = null },
            onConfirm = { selected ->
                coroutineScope.launch {
                    submitVpToken(context, activity, vpTokenService, selected, responseUri.value, snackbarHostState)
                }
                selectedClaims.value = null // Dismiss dialog after confirming selection
            }
        )
    }
}