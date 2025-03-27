package com.example.eudiwemu

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.authlete.sd.Disclosure
import com.example.eudiwemu.security.getEncryptedPrefs
import com.example.eudiwemu.service.IssuanceService
import com.example.eudiwemu.service.VpTokenService
import com.example.eudiwemu.ui.LoginScreen
import com.example.eudiwemu.ui.WalletScreen
import com.example.eudiwemu.ui.viewmodel.AuthenticationViewModel
import com.nimbusds.jwt.JWTParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.time.Instant

class MainActivity : FragmentActivity() {
    private val issuanceService: IssuanceService by inject()
    private val vpTokenService: VpTokenService by inject()

    private val isAuthenticated = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            try {
                val prefs = getEncryptedPrefs(this@MainActivity.applicationContext, this@MainActivity)
                val accessTokenString = prefs.getString("access_token", null)

                val isValid = accessTokenString?.let { token ->
                    try {
                        val jwt = JWTParser.parse(token)
                        jwt.jwtClaimsSet.expirationTime?.toInstant()?.isAfter(Instant.now()) == true
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error decoding JWT or accessing claims", e)
                        false
                    }
                } ?: false

                if (isValid) {
                    isAuthenticated.value = true
                } else {
                    isAuthenticated.value = false
                    prefs.edit().remove("access_token").apply()
                    Log.d("MainActivity", "Access token is invalid or expired.")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to get encrypted prefs or authenticate", e)
                isAuthenticated.value = false
            }

            // Set the content after authentication check
            setContent {
                MainNavHost(
                    activity = this@MainActivity,
                    intent = intent,
                    issuanceService = issuanceService,
                    vpTokenService = vpTokenService,
                    isAuthenticated = isAuthenticated.value,
                )
            }
        }
    }

}

@Composable
fun MainNavHost(
    activity: FragmentActivity,
    intent: Intent?,
    issuanceService: IssuanceService,
    vpTokenService: VpTokenService,
    isAuthenticated: Boolean
) {
    val navController = rememberNavController()
    val startDestination = remember(isAuthenticated) {
        if (isAuthenticated) "wallet_app_screen" else "login_screen"
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable("login_screen") {
            val viewModel: AuthenticationViewModel = viewModel() // Use the viewModel() function
            LoginScreen(
                activity = activity,
                viewModel = viewModel,
                navController = navController,
                issuanceService = issuanceService
            )
        }
        composable("wallet_app_screen") {
            WalletScreen(
                activity = activity,
                intent = intent,
                context = LocalContext.current,
                issuanceService = issuanceService,
                vpTokenService = vpTokenService
            )
        }
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
