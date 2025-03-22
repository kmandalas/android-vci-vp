package com.example.eudiwemu

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.unit.dp
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.eudiwemu.service.IssuanceService
import com.example.eudiwemu.service.VpTokenService
import com.example.eudiwemu.ui.CredentialCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {
    // Inject dependencies using Koin
    private val issuanceService: IssuanceService by inject()
    private val vpTokenService: VpTokenService by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WalletApp(
                applicationContext,
                intent,
                issuanceService,
                vpTokenService
            )
        }
    }
}

//@Composable
//fun WalletApp(context: Context,
//              intent: Intent?,
//              issuanceService: IssuanceService,
//              vpTokenService: VpTokenService) {
//    // MutableState for clientId and requestUri
//    val clientId = remember { mutableStateOf("") }
//    val requestUri = remember { mutableStateOf("") }
//    var status by remember { mutableStateOf("Press the button to request VC") }
//
//    // Handle the deep link data if available
//    LaunchedEffect(intent) {
//        handleDeepLink(context, intent, clientId, requestUri, vpTokenService)
//    }
//
//    Scaffold { paddingValues ->
//        Box(
//            modifier = Modifier
//                .fillMaxSize()
//                .padding(paddingValues),
//            contentAlignment = Alignment.Center
//        ) {
//            Column(horizontalAlignment = Alignment.CenterHorizontally) {
//                Text(text = status, modifier = Modifier.padding(16.dp))
//                Spacer(modifier = Modifier.height(16.dp))
//                Button(onClick = {
//                    requestVC(context, issuanceService) { result ->
//                        status = result
//                    }
//                }) {
//                    Text("Request VC")
//                }
//
//                // Display extracted deep link info
//                if (clientId.value.isNotEmpty() && requestUri.value.isNotEmpty()) {
//                    Text("Client ID: ${clientId.value}")
//                    Text("Request URI: ${requestUri.value}")
//                }
//            }
//        }
//    }
//}

//@Composable
//fun WalletApp(
//    context: Context,
//    intent: Intent?,
//    issuanceService: IssuanceService,
//    vpTokenService: VpTokenService
//) {
//    val clientId = remember { mutableStateOf("") }
//    val requestUri = remember { mutableStateOf("") }
//    var status by remember { mutableStateOf("Press the button to request VC") }
//    var credentialClaims by remember { mutableStateOf<Map<String, String>?>(null) }
//
//    LaunchedEffect(intent) {
//        handleDeepLink(context, intent, clientId, requestUri, vpTokenService)
//    }
//
//    Scaffold { paddingValues ->
//        Box(
//            modifier = Modifier
//                .fillMaxSize()
//                .padding(paddingValues),
//            contentAlignment = Alignment.Center
//        ) {
//            Column(horizontalAlignment = Alignment.CenterHorizontally) {
//                Text(text = status, modifier = Modifier.padding(16.dp))
//                Spacer(modifier = Modifier.height(16.dp))
//
//                Button(onClick = {
//                    requestVC(context, issuanceService, { result ->
//                        status = result
//                    }) { claims ->
//                        credentialClaims = claims // Update UI with received claims
//                    }
//                }) {
//                    Text("Request VC")
//                }
//
//                credentialClaims?.let { CredentialCard(it) } // Show card if claims exist
//            }
//        }
//    }
//}

@Composable
fun WalletApp(
    context: Context,
    intent: Intent?,
    issuanceService: IssuanceService,
    vpTokenService: VpTokenService
) {
    val clientId = remember { mutableStateOf("") }
    val requestUri = remember { mutableStateOf("") }
    var credentialClaims by remember { mutableStateOf<Map<String, String>?>(null) }
    var isLoading by remember { mutableStateOf(false) }  // Add loading state
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(intent) {
        handleDeepLink(context, intent, clientId, requestUri, vpTokenService)
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
                    CircularProgressIndicator() // Show loading indicator when isLoading is true
                } else {
                    Button(onClick = {
                        isLoading = true  // Start loading
                        requestVC(context, issuanceService, { result ->
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    message = result,
                                    actionLabel = "Dismiss",
                                    duration = SnackbarDuration.Short
                                )
                            }
                            isLoading = false  // Stop loading
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
}


// Function to handle deep link and extract data
fun handleDeepLink(context: Context,
                   intent: Intent?,
                   clientId: MutableState<String>,
                   requestUri: MutableState<String>,
                   vpTokenService: VpTokenService) {
    intent?.data?.let { deepLinkUri ->
        // Extract client_id and request_uri from the deep link
        val extractedClientId = deepLinkUri.getQueryParameter("client_id")
        val extractedRequestUri = deepLinkUri.getQueryParameter("request_uri")

        // Update the state to reflect the deep link parameters
        extractedClientId?.let { clientId.value = it }
        extractedRequestUri?.let { requestUri.value = it }

        // Log for debugging
        Log.d("DeepLink", "Client ID: $extractedClientId")
        Log.d("DeepLink", "Request URI: $extractedRequestUri")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = vpTokenService.getRequestObject(extractedRequestUri.orEmpty())
                Log.d("Request Definition received: ",  result.toString())

                val prefs = getEncryptedPrefs(context)
                val storedCredential = prefs.getString("stored_vc", null).orEmpty()
                val claims = vpTokenService.extractRequestedClaims(result, storedCredential)
                val vpToken = vpTokenService.createVP(storedCredential, claims)
                Log.d("Requested claims: ", claims.toString())
                vpTokenService.sendVpTokenToVerifier(vpToken.toString(), result.response_uri)
            } catch (e: Exception) {
                Log.e("WalletApp", "Error during VP handling", e)
            } finally {
                //...
            }
        }
    }
}

// Request a predefined VC
//fun requestVC(
//    context: Context,
//    issuanceService: IssuanceService,
//    onResult: (String) -> Unit,
//    onCredentialReceived: (Map<String, String>) -> Unit // Callback for UI
//) {
//    val prefs = getEncryptedPrefs(context)
//    CoroutineScope(Dispatchers.IO).launch {
//        try {
//            val accessToken = issuanceService.obtainAccessToken()
//            val nonce = issuanceService.getNonce(accessToken.access_token)
//            val jwtProof = issuanceService.createJwtProof(nonce)
//            val storedCredential = issuanceService.requestCredential(accessToken.access_token, jwtProof)
//
//            prefs.edit().putString("stored_vc", storedCredential).apply()
//            // Simulating extraction of claims (replace with real parsing)
//            val claims = mapOf(
//                "Company Name" to "Example Corp",
//                "Given Name" to "John",
//                "Age" to "30"
//            )
//
//            withContext(Dispatchers.Main) {
//                onResult("VC stored securely")
//                onCredentialReceived(claims) // Send claims to UI
//            }
//        } catch (e: Exception) {
//            Log.e("WalletApp", "Error requesting VC", e)
//            withContext(Dispatchers.Main) { onResult("Error: ${e.message}") }
//        } finally {
//            // ...
//        }
//    }
//}

fun requestVC(
    context: Context,
    issuanceService: IssuanceService,
    onResult: (String) -> Unit,
    onCredentialReceived: (Map<String, String>) -> Unit
) {
    val prefs = getEncryptedPrefs(context)

    CoroutineScope(Dispatchers.IO).launch {
        try {
            val accessToken = issuanceService.obtainAccessToken()
            val nonce = issuanceService.getNonce(accessToken.access_token)
            val jwtProof = issuanceService.createJwtProof(nonce)
            val storedCredential = issuanceService.requestCredential(accessToken.access_token, jwtProof)

            prefs.edit().putString("stored_vc", storedCredential).apply()

            val claims = mapOf(
                "Company Name" to "Cognity",
                "Given Name" to "Kyriakos Mandalas",
                "Job Title" to "Kudu Ambassador"
            )

            withContext(Dispatchers.Main) {
                onResult("VC stored securely") // Show success message in snackbar
                onCredentialReceived(claims)  // Update UI with claims
            }
        } catch (e: Exception) {
            Log.e("WalletApp", "Error requesting VC", e)
            withContext(Dispatchers.Main) {
                onResult("Error: ${e.message}") // Show error in snackbar
            }
        }
    }
}


// Enc. Shared prefs handle
fun getEncryptedPrefs(context: Context): SharedPreferences {
    val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        //.setUserAuthenticationRequired(true) --> todo
        .build()

    return EncryptedSharedPreferences.create(
        context,
        "wallet_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
}


