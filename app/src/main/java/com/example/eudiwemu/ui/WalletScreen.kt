package com.example.eudiwemu.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.MutableState
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
import com.example.eudiwemu.config.AppConfig
import com.example.eudiwemu.security.PkceManager
import com.example.eudiwemu.security.getEncryptedPrefs
import com.example.eudiwemu.service.IssuanceService
import com.example.eudiwemu.service.VpTokenService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

//    LaunchedEffect(Unit) {
//        isLoading = true
//        try {
//            val result = requestVC(activity, context, issuanceService)
//            if (result.isSuccess) {
//                val (message, claims) = result.getOrNull()!!
//                credentialClaims = claims
//                snackbarHostState.showSnackbar(message)
//            } else {
//                snackbarHostState.showSnackbar("❌ Error: ${result.exceptionOrNull()?.message}")
//            }
//        } catch (e: Exception) {
//            Log.e("WalletScreen", "Error requesting VC", e)
//            snackbarHostState.showSnackbar("❌ Error requesting VC: ${e.message}")
//        } finally {
//            isLoading = false
//        }
//    }


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
                                val prefs = getEncryptedPrefs(context, activity)
                                val accessToken = prefs.getString("access_token", null)

                                if (accessToken.isNullOrEmpty()) {
                                    // No access token => need to start OAuth Authorization
                                    startAuthorizationFlow(activity, context)
                                    isLoading = false // Stop loading spinner until user comes back
                                } else {
                                    // Already have token => can request VC
                                    val result = requestVC(activity, context, issuanceService)

                                    isLoading = false
                                    if (result.isSuccess) {
                                        val (message, claims) = result.getOrNull()!!
                                        credentialClaims = claims
                                        snackbarHostState.showSnackbar(message)
                                    } else {
                                        snackbarHostState.showSnackbar("❌ Error: ${result.exceptionOrNull()?.message}")
                                    }
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

fun startAuthorizationFlow(activity: FragmentActivity, context: Context) {
    val codeChallenge = PkceManager.generateAndStoreCodeChallenge(context)

    val authorizationUri = Uri.Builder()
        .scheme("http")
        .encodedAuthority(AppConfig.AUTH_SERVER_HOST)
        .path("/oauth2/authorize")
        .appendQueryParameter("response_type", "code")
        .appendQueryParameter("client_id", "wallet-client")
        .appendQueryParameter("scope", "VerifiablePortableDocumentA1")
        .appendQueryParameter("redirect_uri", "myapp://callback")
        .appendQueryParameter("code_challenge", codeChallenge)
        .appendQueryParameter("code_challenge_method", "S256")
        .build()

    val intent = Intent(Intent.ACTION_VIEW, authorizationUri)
    activity.startActivity(intent)
}


// Function to handle VC issuance & storage
suspend fun requestVC(
    activity: FragmentActivity,
    context: Context,
    issuanceService: IssuanceService
): Result<Pair<String, Map<String, String>>> {
    return try {
        // Get encrypted preferences (suspend function)
        val prefs = getEncryptedPrefs(context, activity)

        // Access token from preferences
        val accessToken = prefs.getString("access_token", null)

        if (accessToken.isNullOrEmpty()) {
            // Handle the case where the access token is missing
            return Result.failure(Exception("❌ Access token is missing!"))
        }

        // Proceed with issuance
        val nonce = issuanceService.getNonce(accessToken)
        val jwtProof = issuanceService.createJwtProof(nonce)
        val storedCredential = issuanceService.requestCredential(accessToken, jwtProof)
        val claims = issuanceService.decodeCredential(storedCredential)

        // Store the credential securely
        prefs.edit().putString("stored_vc", storedCredential).apply()

        // Return success with message and claims
        Result.success("✅ VC stored securely" to claims)

    } catch (e: Exception) {
        Log.e("WalletApp", "Error requesting VC", e)
        // Return failure with the exception message
        Result.failure(e)
    }
}

// Function to handle deep link and extract data
suspend fun handleDeepLink(
    context: Context,
    activity: FragmentActivity,
    intent: Intent?,
    clientId: MutableState<String>,
    requestUri: MutableState<String>,
    vpTokenService: VpTokenService,
    selectedClaims: MutableState<List<Disclosure>?>, // Store claims for UI
    responseUri: MutableState<String>,
) {
    intent?.data?.let { deepLinkUri ->
        val extractedClientId = deepLinkUri.getQueryParameter("client_id")
        val extractedRequestUri = deepLinkUri.getQueryParameter("request_uri")

        extractedClientId?.let { clientId.value = it }
        extractedRequestUri?.let { requestUri.value = it }

        try {
            val result = withContext(Dispatchers.IO) {
                vpTokenService.getRequestObject(extractedRequestUri.orEmpty())
            }
            responseUri.value = result.response_uri

            // Fetch encrypted preferences securely
            val prefs = getEncryptedPrefs(context, activity) // Now a suspend function

            val storedCredential = withContext(Dispatchers.IO) {
                prefs.getString("stored_vc", null).orEmpty()
            }
            val claims = withContext(Dispatchers.IO) {
                vpTokenService.extractRequestedClaims(result, storedCredential)
            }

            // Update UI with selected claims
            withContext(Dispatchers.Main) {
                selectedClaims.value = claims
            }
        } catch (e: Exception) {
            Log.e("WalletApp", "Error during VP handling", e)
        }
    }
}

// Function to submit the VP token based on selected user claims
suspend fun submitVpToken(
    context: Context,
    activity: FragmentActivity,
    vpTokenService: VpTokenService,
    selectedClaims: List<Disclosure>,
    responseUri: String,
    snackbarHostState: SnackbarHostState
) {
    try {
        val prefs = getEncryptedPrefs(context, activity) // Now a suspend function

        val storedCredential = withContext(Dispatchers.IO) {
            prefs.getString("stored_vc", null).orEmpty()
        }
        val vpToken = withContext(Dispatchers.IO) {
            vpTokenService.createVP(storedCredential, selectedClaims)
        }

        val serverResponse = withContext(Dispatchers.IO) {
            vpTokenService.sendVpTokenToVerifier(vpToken.toString(), responseUri)
        }

        // Show server response in Snackbar
        withContext(Dispatchers.Main) {
            snackbarHostState.showSnackbar(
                message = "Verifier Response: $serverResponse",
                actionLabel = "Dismiss",
                duration = SnackbarDuration.Long
            )
        }
    } catch (e: Exception) {
        Log.e("WalletApp", "Error sending VP Token", e)

        // Show error message in Snackbar
        withContext(Dispatchers.Main) {
            snackbarHostState.showSnackbar(
                message = "❌ Error: ${e.message}",
                actionLabel = "Dismiss",
                duration = SnackbarDuration.Long
            )
        }
    }
}
