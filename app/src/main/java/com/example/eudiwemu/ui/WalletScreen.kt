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

    // Handle deep links
    LaunchedEffect(intent) {
        handleDeepLink(
            context = context,
            activity = activity,
            intent = intent,
            clientId = clientId,
            requestUri = requestUri,
            issuanceService = issuanceService,
            vpTokenService = vpTokenService,
            selectedClaims = selectedClaims,
            responseUri = responseUri,
            isLoadingSetter = { isLoading = it },
            setCredentialClaims = { credentialClaims = it },
            showSnackbar = { message ->
                snackbarHostState.showSnackbar(message)
            }
        )
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

// Function to handle deep link and extract data
suspend fun handleDeepLink(
    context: Context,
    activity: FragmentActivity,
    intent: Intent?,
    clientId: MutableState<String>,
    requestUri: MutableState<String>,
    issuanceService: IssuanceService,
    vpTokenService: VpTokenService,
    selectedClaims: MutableState<List<Disclosure>?>,
    responseUri: MutableState<String>,
    isLoadingSetter: (Boolean) -> Unit,
    setCredentialClaims: (Map<String, String>) -> Unit,
    showSnackbar: suspend (String) -> Unit
) {
    val uri = intent?.data ?: return
    when (uri.scheme) {
        "myapp" -> handleOAuthDeepLink(
            uri, context, activity,
            issuanceService, isLoadingSetter,
            setCredentialClaims, showSnackbar
        )

        "eudi-openid4vp" -> handleVpTokenDeepLink(
            uri, context, activity,
            vpTokenService, clientId, requestUri,
            responseUri, selectedClaims
        )

        else -> Log.w("WalletApp", "Unknown deep link scheme: ${uri.scheme}")
    }
}

private suspend fun handleOAuthDeepLink(
    uri: Uri,
    context: Context,
    activity: FragmentActivity,
    issuanceService: IssuanceService,
    isLoadingSetter: (Boolean) -> Unit,
    setCredentialClaims: (Map<String, String>) -> Unit,
    showSnackbar: suspend (String) -> Unit
) {
    val code = uri.getQueryParameter("code") ?: return
    Log.d("OAuth", "Authorization code: $code")

    val codeVerifier = PkceManager.getCodeVerifier(context)
    val tokenResult = withContext(Dispatchers.IO) {
        issuanceService.exchangeAuthorizationCodeForToken(code, codeVerifier)
    }

    if (tokenResult.isSuccess) {
        val accessToken = tokenResult.getOrNull()
        val prefs = getEncryptedPrefs(context, activity)
        prefs.edit().putString("access_token", accessToken).apply()

        Log.d("OAuth", "Access token saved!")

        // Automatically request VC
        withContext(Dispatchers.Main) {
            isLoadingSetter(true)
        }

        try {
            val vcResult = requestVC(activity, context, issuanceService)
            withContext(Dispatchers.Main) {
                isLoadingSetter(false)
                if (vcResult.isSuccess) {
                    val (message, claims) = vcResult.getOrNull()!!
                    setCredentialClaims(claims)
                    showSnackbar(message)
                } else {
                    showSnackbar("❌ Error: ${vcResult.exceptionOrNull()?.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("WalletApp", "Error requesting VC after OAuth", e)
            withContext(Dispatchers.Main) {
                isLoadingSetter(false)
                showSnackbar("❌ Error requesting VC: ${e.message}")
            }
        }
    } else {
        Log.e("OAuth", "Failed to exchange code: ${tokenResult.exceptionOrNull()?.message}")
    }
}

private suspend fun handleVpTokenDeepLink(
    uri: Uri,
    context: Context,
    activity: FragmentActivity,
    vpTokenService: VpTokenService,
    clientId: MutableState<String>,
    requestUri: MutableState<String>,
    responseUri: MutableState<String>,
    selectedClaims: MutableState<List<Disclosure>?>
) {
    val extractedClientId = uri.getQueryParameter("client_id")
    val extractedRequestUri = uri.getQueryParameter("request_uri")

    extractedClientId?.let { clientId.value = it }
    extractedRequestUri?.let { requestUri.value = it }

    try {
        val requestObject = withContext(Dispatchers.IO) {
            vpTokenService.getRequestObject(extractedRequestUri.orEmpty())
        }

        responseUri.value = requestObject.response_uri

        val prefs = getEncryptedPrefs(context, activity)
        val storedCredential = withContext(Dispatchers.IO) {
            prefs.getString("stored_vc", null).orEmpty()
        }

        val claims = withContext(Dispatchers.IO) {
            vpTokenService.extractRequestedClaims(requestObject, storedCredential)
        }

        withContext(Dispatchers.Main) {
            selectedClaims.value = claims
        }
    } catch (e: Exception) {
        Log.e("WalletApp", "Error handling VP Token flow", e)
    }
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
