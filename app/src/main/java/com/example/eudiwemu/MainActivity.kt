package com.example.eudiwemu

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.authlete.sd.Disclosure
import com.example.eudiwemu.security.getEncryptedPrefs
import com.example.eudiwemu.service.IssuanceService
import com.example.eudiwemu.service.VpTokenService
import com.example.eudiwemu.ui.ClaimSelectionDialog
import com.example.eudiwemu.ui.CredentialCard
import com.example.eudiwemu.ui.LoginScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class MainActivity : FragmentActivity() {
    private val issuanceService: IssuanceService by inject()
    private val vpTokenService: VpTokenService by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // No need to check for stored token; go straight to the LoginScreen
        setContent {
            MainNavHost(
                activity = this,
                intent,
                issuanceService = issuanceService,
                vpTokenService = vpTokenService
            )
        }
    }
}

@Composable
fun MainNavHost(
    activity: FragmentActivity,
    intent: Intent?,
    issuanceService: IssuanceService,
    vpTokenService: VpTokenService
) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "login_screen") {
        composable("login_screen") {
            LoginScreen(
                activity = activity,
                viewModel = AuthenticationViewModel(),
                navController = navController,
                issuanceService = issuanceService
            )
        }
        composable("wallet_app_screen") {
            WalletApp(
                activity = activity,
                intent = intent,
                context = LocalContext.current,
                issuanceService = issuanceService,
                vpTokenService = vpTokenService
            )
        }
    }
}

@Composable
fun WalletApp(
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
        // Asynchronously fetch preferences
        getEncryptedPrefs(context, activity as FragmentActivity, { prefs ->
            val storedCredential = prefs.getString("stored_vc", null)
            if (!storedCredential.isNullOrEmpty()) {
                try {
                    val claims = issuanceService.decodeCredential(storedCredential)
                    credentialClaims = claims
                } catch (e: Exception) {
                    Log.e("WalletApp", "Error decoding stored VC", e)
                }
            }
        }, { exception ->
            Log.e("WalletApp", "Error fetching encrypted prefs: $exception")
        })
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
                        isLoading = true
                        requestVC(activity, context, issuanceService, { result ->
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    message = result,
                                    actionLabel = "Dismiss",
                                    duration = SnackbarDuration.Short
                                )
                            }
                            isLoading = false
                        }) { claims ->
                            credentialClaims = claims
                        }
                    }) {
                        Text("Request VC")
                    }
                }

                credentialClaims?.let { CredentialCard(it) }
            }
        }
    }

    // Show claim selection dialog when claims are available
    selectedClaims.value?.let { claims ->
        ClaimSelectionDialog(
            claims = claims,
            onDismiss = { selectedClaims.value = null },
            onConfirm = { selected ->
                submitVpToken(context, activity, vpTokenService, selected, responseUri.value, snackbarHostState)
                selectedClaims.value = null // Dismiss dialog after confirming selection
            }
        )
    }
}

fun requestVC(
    activity: FragmentActivity,
    context: Context,
    issuanceService: IssuanceService,
    onResult: (String) -> Unit,
    onCredentialReceived: (Map<String, String>) -> Unit
) {
    // Start by asynchronously fetching encrypted preferences
    getEncryptedPrefs(context, activity, { prefs ->
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val accessToken = prefs.getString("access_token", "")
                if (accessToken.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) {
                        onResult("❌ Access token is missing!")
                    }
                    return@launch
                }

                val nonce = issuanceService.getNonce(accessToken)
                val jwtProof = issuanceService.createJwtProof(nonce)
                val storedCredential = issuanceService.requestCredential(accessToken, jwtProof)
                val claims = issuanceService.decodeCredential(storedCredential)

                // Store VC securely
                prefs.edit().putString("stored_vc", storedCredential).apply()

                // Inform the UI about success
                withContext(Dispatchers.Main) {
                    onResult("✅ VC stored securely")
                    onCredentialReceived(claims)
                }
            } catch (e: Exception) {
                Log.e("WalletApp", "Error requesting VC", e)
                withContext(Dispatchers.Main) {
                    onResult("❌ Error: ${e.message}") // Show error in snackbar
                }
            }
        }
    }, { exception ->
        Log.e("WalletApp", "Error fetching encrypted prefs: $exception")
        onResult("❌ Failed to retrieve preferences.")
    })
}

// Function to handle deep link and extract data
fun handleDeepLink(
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

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = vpTokenService.getRequestObject(extractedRequestUri.orEmpty())
                responseUri.value = result.response_uri

                // Fetch encrypted preferences securely
                withContext(Dispatchers.Main) {
                    getEncryptedPrefs(
                        context,
                        activity,
                        onSuccess = { prefs ->
                            CoroutineScope(Dispatchers.IO).launch {
                                val storedCredential = prefs.getString("stored_vc", null).orEmpty()
                                val claims = vpTokenService.extractRequestedClaims(result, storedCredential)

                                // Update UI with selected claims
                                withContext(Dispatchers.Main) {
                                    selectedClaims.value = claims
                                }
                            }
                        },
                        onFailure = { e ->
                            Log.e("WalletApp", "Failed to get encrypted prefs", e)
                        }
                    )
                }

            } catch (e: Exception) {
                Log.e("WalletApp", "Error during VP handling", e)
            }
        }
    }
}

fun submitVpToken(
    context: Context,
    activity: FragmentActivity,
    vpTokenService: VpTokenService,
    selectedClaims: List<Disclosure>,
    responseUri: String,
    snackbarHostState: SnackbarHostState
) {
    getEncryptedPrefs(
        context,
        activity,
        onSuccess = { prefs ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val storedCredential = prefs.getString("stored_vc", null).orEmpty()
                    val vpToken = vpTokenService.createVP(storedCredential, selectedClaims)

                    // Capture response from server
                    val serverResponse = vpTokenService.sendVpTokenToVerifier(vpToken.toString(), responseUri)

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
        },
        onFailure = { e ->
            Log.e("WalletApp", "Failed to get encrypted prefs", e)

            CoroutineScope(Dispatchers.Main).launch {
                snackbarHostState.showSnackbar(
                    message = "❌ Failed to access secure storage",
                    actionLabel = "Dismiss",
                    duration = SnackbarDuration.Long
                )
            }
        }
    )
}