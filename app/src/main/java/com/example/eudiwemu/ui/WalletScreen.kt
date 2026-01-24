package com.example.eudiwemu.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
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
import com.example.eudiwemu.QrScannerActivity
import com.example.eudiwemu.config.AppConfig
import com.example.eudiwemu.dto.AuthorizationRequestResponse
import com.example.eudiwemu.security.PkceManager
import com.example.eudiwemu.security.getEncryptedPrefs
import com.example.eudiwemu.service.IssuanceService
import com.example.eudiwemu.service.VpTokenService
import com.example.eudiwemu.service.WiaService
import com.example.eudiwemu.service.WuaService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun WalletScreen(
    activity: FragmentActivity,
    context: Context,
    intent: Intent?,
    issuanceService: IssuanceService,
    vpTokenService: VpTokenService,
    wuaService: WuaService,
    wiaService: WiaService
) {
    val clientId = remember { mutableStateOf("") }
    val requestUri = remember { mutableStateOf("") }
    val responseUri = remember { mutableStateOf("") }
    var credentialClaims by remember { mutableStateOf<Map<String, String>?>(null) }
    var wuaInfo by remember { mutableStateOf<Map<String, Any>?>(null) }
    var wiaInfo by remember { mutableStateOf<Map<String, Any>?>(null) }
    val selectedClaims = remember { mutableStateOf<List<Disclosure>?>(null) }
    val clientName = remember { mutableStateOf("") }
    val logoUri = remember { mutableStateOf("") }
    val purpose = remember { mutableStateOf("") }
    val nonce = remember { mutableStateOf("") }
    val authRequest = remember { mutableStateOf<AuthorizationRequestResponse?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    var expanded by remember { mutableStateOf(false) }
    // Map of display label to credential configuration ID
    val credentialTypes = mapOf(
        "Portable Document A1 (PDA1)" to "eu.europa.ec.eudi.pda1_sd_jwt_vc"
    )
    var selectedLabel by remember { mutableStateOf("") }
    var selectedValue by remember { mutableStateOf("") }

    // Load stored credential and attestations on launch
    LaunchedEffect(Unit) {
        try {
            // Initialize services with activity context for encrypted prefs access
            wuaService.initWithActivity(activity)
            wiaService.initWithActivity(activity)
            issuanceService.initWithActivity(activity)

            // Load WUA using service method
            val storedWua = wuaService.getStoredWua()
            if (!storedWua.isNullOrEmpty()) {
                try {
                    wuaInfo = wuaService.decodeWuaCredential(storedWua)
                } catch (e: Exception) {
                    Log.e("WalletApp", "Error decoding stored WUA", e)
                }
            }

            // Load WIA using service method
            val storedWia = wiaService.getStoredWia()
            if (!storedWia.isNullOrEmpty()) {
                try {
                    wiaInfo = wiaService.decodeWiaCredential(storedWia)
                } catch (e: Exception) {
                    Log.e("WalletApp", "Error decoding stored WIA", e)
                }
            }

            // Load VC using service method
            val storedCredential = issuanceService.getStoredCredential()
            if (!storedCredential.isNullOrEmpty()) {
                try {
                    credentialClaims = issuanceService.decodeCredential(storedCredential)
                } catch (e: Exception) {
                    Log.e("WalletApp", "Error decoding stored VC", e)
                }
            }
        } catch (e: Exception) {
            Log.e("WalletApp", "Error initializing services: $e")
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
            wuaService = wuaService,
            selectedClaims = selectedClaims,
            clientName = clientName,
            logoUri = logoUri,
            purpose = purpose,
            nonce = nonce,
            authRequest = authRequest,
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
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                // Attestation Carousel (WIA and WUA cards)
                AttestationCarousel(wiaInfo = wiaInfo, wuaInfo = wuaInfo)

                Spacer(modifier = Modifier.height(8.dp))

                // Dropdown Menu
                Box {
                    OutlinedTextField(
                        value = selectedLabel,
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
                        credentialTypes.forEach { (label, value) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    selectedLabel = label
                                    selectedValue = value
                                    expanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (isLoading) {
                    CircularProgressIndicator()
                } else {
                    Button(
                        onClick = {
                            isLoading = true
                            coroutineScope.launch {
                                requestCredential(
                                    context = context,
                                    activity = activity,
                                    issuanceService = issuanceService,
                                    wuaService = wuaService,
                                    snackbarHostState = snackbarHostState,
                                    onLoadingChanged = { isLoading = it },
                                    onSuccess = { claims -> credentialClaims = claims }
                                )
                            }
                        },
                        enabled = selectedValue.isNotEmpty()
                    ) {
                        Text("Request VC")
                    }
                }

                credentialClaims?.let {
                    CredentialCard(
                        claims = it,
                        onDelete = {
                            coroutineScope.launch {
                                try {
                                    issuanceService.removeCredential()
                                    credentialClaims = null
                                    snackbarHostState.showSnackbar("Credential deleted")
                                } catch (e: Exception) {
                                    Log.e("WalletApp", "Error deleting credential", e)
                                    snackbarHostState.showSnackbar("Error deleting credential")
                                }
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val intent = Intent(context, QrScannerActivity::class.java)
                            activity.startActivity(intent)
                        }
                    ) {
                        Text("Scan QR")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // Show claim selection dialog when claims are available
    selectedClaims.value?.let { claims ->
        ClaimSelectionDialog(
            clientName = clientName.value,
            logoUri = logoUri.value,
            purpose = purpose.value,
            claims = claims,
            onDismiss = { selectedClaims.value = null },
            onConfirm = { selected ->
                coroutineScope.launch {
                    submitVpToken(
                        context, vpTokenService, issuanceService, selected,
                        responseUri.value, clientId.value, nonce.value,
                        authRequest.value, snackbarHostState
                    )
                }
                selectedClaims.value = null // Dismiss dialog after confirming selection
            }
        )
    }
}

/**
 * Starts the authorization flow using PAR (Pushed Authorization Request).
 *
 * PAR enables early WIA validation before the user sees the authorization screen,
 * improving security by validating the wallet instance attestation upfront.
 *
 * Flow:
 * 1. Generate PKCE code challenge
 * 2. Push authorization parameters to PAR endpoint with WIA headers
 * 3. Receive request_uri from PAR response
 * 4. Redirect user to authorization endpoint with request_uri
 */
suspend fun startAuthorizationFlowWithPar(
    activity: FragmentActivity,
    context: Context,
    issuanceService: IssuanceService,
    onError: (String) -> Unit
) {
    val codeChallenge = PkceManager.generateAndStoreCodeChallenge(context)

    // Step 1: Push authorization request to PAR endpoint
    val parResult = withContext(Dispatchers.IO) {
        issuanceService.pushAuthorizationRequest(codeChallenge)
    }

    if (parResult.isFailure) {
        val errorMessage = parResult.exceptionOrNull()?.message ?: "Unknown PAR error"
        Log.e("WalletApp", "PAR request failed: $errorMessage")
        onError("PAR request failed: $errorMessage")
        return
    }

    val requestUri = parResult.getOrThrow()
    Log.d("WalletApp", "PAR success, received request_uri: $requestUri")

    // Step 2: Redirect to authorization endpoint with request_uri
    // Per RFC 9126, only client_id and request_uri are needed - scope comes from PAR storage
    val authorizationUri = Uri.Builder()
        .scheme("http")
        .encodedAuthority(AppConfig.AUTH_SERVER_HOST)
        .path("/oauth2/authorize")
        .appendQueryParameter("client_id", AppConfig.CLIENT_ID)
        .appendQueryParameter("request_uri", requestUri)
        .build()

    Log.d("WalletApp", "Redirecting to authorization: $authorizationUri")
    val intent = Intent(Intent.ACTION_VIEW, authorizationUri)
    activity.startActivity(intent)
}

/**
 * Legacy authorization flow without PAR.
 * Kept as fallback in case PAR is not supported.
 */
fun startAuthorizationFlowLegacy(activity: FragmentActivity, context: Context) {
    val codeChallenge = PkceManager.generateAndStoreCodeChallenge(context)

    val authorizationUri = Uri.Builder()
        .scheme("http")
        .encodedAuthority(AppConfig.AUTH_SERVER_HOST)
        .path("/oauth2/authorize")
        .appendQueryParameter("response_type", "code")
        .appendQueryParameter("client_id", AppConfig.CLIENT_ID)
        .appendQueryParameter("scope", AppConfig.SCOPE)
        .appendQueryParameter("redirect_uri", AppConfig.REDIRECT_URI)
        .appendQueryParameter("code_challenge", codeChallenge)
        .appendQueryParameter("code_challenge_method", "S256")
        .build()

    val intent = Intent(Intent.ACTION_VIEW, authorizationUri)
    activity.startActivity(intent)
}

suspend fun requestCredential(
    context: Context,
    activity: FragmentActivity,
    issuanceService: IssuanceService,
    wuaService: WuaService,
    snackbarHostState: SnackbarHostState,
    onLoadingChanged: (Boolean) -> Unit,
    onSuccess: (Map<String, String>) -> Unit
) {
    try {
        val prefs = getEncryptedPrefs(context, activity)
        val accessToken = prefs.getString("access_token", null)

        if (accessToken.isNullOrEmpty()) {
            startAuthorizationFlowWithPar(activity, context, issuanceService) { error ->
                Log.e("WalletApp", "PAR failed: $error")
                // Fallback to legacy flow if PAR fails
                startAuthorizationFlowLegacy(activity, context)
            }
            onLoadingChanged(false)
        } else {
            val result = submitCredentialRequest(activity, context, issuanceService, wuaService)

            onLoadingChanged(false)
            if (result.isSuccess) {
                val (message, claims) = result.getOrNull()!!
                onSuccess(claims)
                snackbarHostState.showSnackbar(message)
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: ""
                // Check for auth errors (401, expired token, etc.)
                if (isAuthError(errorMsg)) {
                    Log.w("WalletApp", "Token expired or invalid, clearing and restarting auth")
                    prefs.edit { remove("access_token") }
                    snackbarHostState.showSnackbar("Session expired, please authenticate again")
                    startAuthorizationFlowWithPar(activity, context, issuanceService) { error ->
                        Log.e("WalletApp", "PAR failed during re-auth: $error")
                        startAuthorizationFlowLegacy(activity, context)
                    }
                } else {
                    snackbarHostState.showSnackbar("❌ Error: $errorMsg")
                }
            }
        }
    } catch (e: Exception) {
        Log.e("WalletApp", "Error requesting VC", e)
        onLoadingChanged(false)
        val errorMsg = e.message ?: ""
        val prefs = getEncryptedPrefs(context, activity)
        if (isAuthError(errorMsg)) {
            prefs.edit { remove("access_token") }
            snackbarHostState.showSnackbar("Session expired, please authenticate again")
            startAuthorizationFlowWithPar(activity, context, issuanceService) { error ->
                Log.e("WalletApp", "PAR failed during error recovery: $error")
                startAuthorizationFlowLegacy(activity, context)
            }
        } else {
            snackbarHostState.showSnackbar(
                message = "❌ Error requesting VC: ${e.message}",
                actionLabel = "Dismiss",
                duration = SnackbarDuration.Long
            )
        }
    }
}

private fun isAuthError(errorMessage: String): Boolean {
    val lowerMsg = errorMessage.lowercase()
    return lowerMsg.contains("401") ||
            lowerMsg.contains("unauthorized") ||
            lowerMsg.contains("expired") ||
            lowerMsg.contains("invalid_token") ||
            lowerMsg.contains("invalid token")
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
    wuaService: WuaService,
    selectedClaims: MutableState<List<Disclosure>?>,
    clientName: MutableState<String>,
    logoUri: MutableState<String>,
    purpose: MutableState<String>,
    nonce: MutableState<String>,
    authRequest: MutableState<AuthorizationRequestResponse?>,
    responseUri: MutableState<String>,
    isLoadingSetter: (Boolean) -> Unit,
    setCredentialClaims: (Map<String, String>) -> Unit,
    showSnackbar: suspend (String) -> Unit
) {
    val uri = intent?.data ?: return
    when (uri.scheme) {
        "myapp" -> handleOAuthDeepLink(
            uri, context, activity,
            issuanceService, wuaService, isLoadingSetter,
            setCredentialClaims, showSnackbar
        )

        "openid4vp", "haip-vp" -> handleVpTokenDeepLink(
            uri, context, activity,
            vpTokenService, issuanceService, clientId, requestUri,
            responseUri, selectedClaims,
            clientName, logoUri, purpose, nonce, authRequest
        )

        else -> Log.w("WalletApp", "Unknown deep link scheme: ${uri.scheme}")
    }
}

private suspend fun handleOAuthDeepLink(
    uri: Uri,
    context: Context,
    activity: FragmentActivity,
    issuanceService: IssuanceService,
    wuaService: WuaService,
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
        prefs.edit { putString("access_token", accessToken) }

        Log.d("OAuth", "Access token saved!")

        // Automatically request VC
        withContext(Dispatchers.Main) {
            isLoadingSetter(true)
        }

        try {
            val vcResult = submitCredentialRequest(activity, context, issuanceService, wuaService)
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
    issuanceService: IssuanceService,
    clientId: MutableState<String>,
    requestUri: MutableState<String>,
    responseUri: MutableState<String>,
    selectedClaims: MutableState<List<Disclosure>?>,
    clientName: MutableState<String>,
    logoUri: MutableState<String>,
    purpose: MutableState<String>,
    nonce: MutableState<String>,
    authRequest: MutableState<AuthorizationRequestResponse?>,
) {
    val extractedClientId = uri.getQueryParameter("client_id")
    val extractedRequestUri = uri.getQueryParameter("request_uri")

    extractedClientId?.let { clientId.value = it }
    extractedRequestUri?.let { requestUri.value = it }

    try {
        val requestObject = withContext(Dispatchers.IO) {
            vpTokenService.getRequestObject(
                extractedRequestUri.orEmpty(),
                extractedClientId.orEmpty()
            )
        }

        responseUri.value = requestObject.response_uri
        clientName.value = requestObject.client_metadata?.client_name ?: ""
        logoUri.value = requestObject.client_metadata?.logo_uri ?: ""
        purpose.value = requestObject.client_metadata?.purpose ?: "Credential verification request"
        // Store client_id and nonce for VP creation
        clientId.value = requestObject.client_id ?: extractedClientId ?: ""
        nonce.value = requestObject.nonce
        // Store the full request for encryption parameters
        authRequest.value = requestObject

        val storedCredential = withContext(Dispatchers.IO) {
            issuanceService.getStoredCredential().orEmpty()
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
suspend fun submitCredentialRequest(
    activity: FragmentActivity,
    context: Context,
    issuanceService: IssuanceService,
    wuaService: WuaService
): Result<Pair<String, Map<String, String>>> {
    return try {
        // Get encrypted preferences for access token (stored during auth flow)
        val prefs = getEncryptedPrefs(context, activity)

        // Access token from preferences
        val accessToken = prefs.getString("access_token", null)

        if (accessToken.isNullOrEmpty()) {
            // Handle the case where the access token is missing
            return Result.failure(Exception("❌ Access token is missing!"))
        }

        // Get stored WUA using service method (required for key_attestation header)
        val storedWua = wuaService.getStoredWua()
        if (storedWua.isNullOrEmpty()) {
            return Result.failure(Exception("❌ WUA not found - wallet not activated!"))
        }

        // Proceed with issuance (credential storage is now handled by issuanceService.requestCredential)
        val nonce = issuanceService.getNonce(accessToken)
        val jwtProof = issuanceService.createJwtProof(nonce, storedWua)
        val storedCredential = issuanceService.requestCredential(accessToken, jwtProof)
        val claims = issuanceService.decodeCredential(storedCredential)

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
    vpTokenService: VpTokenService,
    issuanceService: IssuanceService,
    selectedClaims: List<Disclosure>,
    responseUri: String,
    clientId: String,
    nonce: String,
    authRequest: AuthorizationRequestResponse?,
    snackbarHostState: SnackbarHostState
) {
    try {
        val storedCredential = withContext(Dispatchers.IO) {
            issuanceService.getStoredCredential().orEmpty()
        }
        val vpToken = withContext(Dispatchers.IO) {
            vpTokenService.createVP(storedCredential, selectedClaims, clientId, nonce)
        }

        val serverResponse = withContext(Dispatchers.IO) {
            if (authRequest != null) {
                vpTokenService.sendVpTokenToVerifier(vpToken.toString(), responseUri, authRequest)
            } else {
                throw IllegalStateException("Authorization request is required")
            }
        }

        // Handle verifier response
        withContext(Dispatchers.Main) {
            try {
                val jsonResponse = JSONObject(serverResponse)
                val redirectUri = jsonResponse.optString("redirect_uri", null)

                if (!redirectUri.isNullOrEmpty()) {
                    // Open redirect_uri in browser (EU verifier flow)
                    val intent = Intent(Intent.ACTION_VIEW, redirectUri.toUri())
                    context.startActivity(intent)
                    snackbarHostState.showSnackbar(
                        message = "✅ Verification successful - opening result",
                        actionLabel = "Dismiss",
                        duration = SnackbarDuration.Short
                    )
                } else {
                    // Show plain response (our local verifier)
                    snackbarHostState.showSnackbar(
                        message = "Verifier Response: $serverResponse",
                        actionLabel = "Dismiss",
                        duration = SnackbarDuration.Long
                    )
                }
            } catch (e: Exception) {
                // Not JSON or no redirect_uri - show as-is
                snackbarHostState.showSnackbar(
                    message = "Verifier Response: $serverResponse",
                    actionLabel = "Dismiss",
                    duration = SnackbarDuration.Long
                )
            }
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
