package com.example.eudiwemu.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
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
import com.example.eudiwemu.config.AppConfig
import com.example.eudiwemu.security.PkceManager
import com.example.eudiwemu.security.getEncryptedPrefs
import com.example.eudiwemu.service.IssuanceService
import com.example.eudiwemu.service.VpTokenService
import com.example.eudiwemu.service.WuaIssuanceService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun WalletScreen(
    activity: FragmentActivity,
    context: Context,
    intent: Intent?,
    issuanceService: IssuanceService,
    vpTokenService: VpTokenService,
    wuaIssuanceService: WuaIssuanceService
) {
    val clientId = remember { mutableStateOf("") }
    val requestUri = remember { mutableStateOf("") }
    val responseUri = remember { mutableStateOf("") }
    var credentialClaims by remember { mutableStateOf<Map<String, String>?>(null) }
    var wuaInfo by remember { mutableStateOf<Map<String, Any>?>(null) }
    val selectedClaims = remember { mutableStateOf<List<Disclosure>?>(null) }
    val clientName = remember { mutableStateOf("") }
    val logoUri = remember { mutableStateOf("") }
    val purpose = remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    var expanded by remember { mutableStateOf(false) }
    val options = listOf("VerifiablePortableDocumentA1")
    var selectedOption by remember { mutableStateOf("") }

    // Load stored credential and WUA on launch
    LaunchedEffect(Unit) {
        try {
            val prefs = getEncryptedPrefs(context, activity)

            // Load WUA
            val storedWua = prefs.getString("stored_wua", null)
            if (!storedWua.isNullOrEmpty()) {
                try {
                    wuaInfo = wuaIssuanceService.decodeWuaCredential(storedWua)
                } catch (e: Exception) {
                    Log.e("WalletApp", "Error decoding stored WUA", e)
                }
            }

            // Load VC
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
            clientName = clientName,
            logoUri = logoUri,
            purpose = purpose,
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

                // WUA Status Card (shown at top if available)
                wuaInfo?.let {
                    WuaStatusCard(it)
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Dropdown Menu
                Box {
                    OutlinedTextField(
                        value = selectedOption,
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
                        options.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    selectedOption = option
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
                                    snackbarHostState = snackbarHostState,
                                    onLoadingChanged = { isLoading = it },
                                    onSuccess = { claims -> credentialClaims = claims }
                                )
                            }
                        },
                        enabled = selectedOption == "VerifiablePortableDocumentA1"
                    ) {
                        Text("Request VC")
                    }
                }

                credentialClaims?.let {
                    CredentialCard(it)
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
    snackbarHostState: SnackbarHostState,
    onLoadingChanged: (Boolean) -> Unit,
    onSuccess: (Map<String, String>) -> Unit
) {
    try {
        val prefs = getEncryptedPrefs(context, activity)
        val accessToken = prefs.getString("access_token", null)

        if (accessToken.isNullOrEmpty()) {
            startAuthorizationFlow(activity, context)
            onLoadingChanged(false)
        } else {
            val result = submitCredentialRequest(activity, context, issuanceService)

            onLoadingChanged(false)
            if (result.isSuccess) {
                val (message, claims) = result.getOrNull()!!
                onSuccess(claims)
                snackbarHostState.showSnackbar(message)
            } else {
                snackbarHostState.showSnackbar("❌ Error: ${result.exceptionOrNull()?.message}")
            }
        }
    } catch (e: Exception) {
        Log.e("WalletApp", "Error requesting VC", e)
        onLoadingChanged(false)
        snackbarHostState.showSnackbar(
            message = "❌ Error requesting VC: ${e.message}",
            actionLabel = "Dismiss",
            duration = SnackbarDuration.Long
        )
    }
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
    clientName: MutableState<String>,
    logoUri: MutableState<String>,
    purpose: MutableState<String>,
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

        "openid4vp" -> handleVpTokenDeepLink(
            uri, context, activity,
            vpTokenService, clientId, requestUri,
            responseUri, selectedClaims,
            clientName, logoUri, purpose
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
            val vcResult = submitCredentialRequest(activity, context, issuanceService)
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
    selectedClaims: MutableState<List<Disclosure>?>,
    clientName: MutableState<String>,
    logoUri: MutableState<String>,
    purpose: MutableState<String>,
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
        clientName.value = requestObject.client_metadata?.client_name ?: ""
        logoUri.value = requestObject.client_metadata?.logo_uri ?: ""
        purpose.value = requestObject.presentation_definition.purpose

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
suspend fun submitCredentialRequest(
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
