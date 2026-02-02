package com.example.eudiwemu.ui

import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.core.content.edit
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavController
import com.example.eudiwemu.R
import com.example.eudiwemu.security.getEncryptedPrefs
import com.example.eudiwemu.security.showBiometricPrompt
import com.example.eudiwemu.service.WiaService
import com.example.eudiwemu.service.WuaService
import com.example.eudiwemu.ui.viewmodel.AuthenticationViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "LoginScreen"

@Composable
fun LoginScreen(
    activity: FragmentActivity,
    viewModel: AuthenticationViewModel,
    navController: NavController,
    wuaService: WuaService,
    wiaService: WiaService
) {

    val isAuthenticated by viewModel.isAuthenticated
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(false) }
    var loadingMessage by remember { mutableStateOf("Authenticating...") }
    var hasTriedAuth by remember { mutableStateOf(false) }
    var showSplash by remember { mutableStateOf(true) }
    var splashAlpha by remember { mutableFloatStateOf(0f) }

    val animatedAlpha by animateFloatAsState(
        targetValue = splashAlpha,
        animationSpec = tween(durationMillis = 800),
        label = "SplashAlpha"
    )

    // Auto-navigation if already authenticated
    LaunchedEffect(isAuthenticated) {
        if (isAuthenticated) {
            navController.navigate("wallet_app_screen") {
                popUpTo("login_screen") { inclusive = true }
            }
        }
    }

    // Perform biometric auth automatically once
    LaunchedEffect(Unit) {
        delay(100)
        splashAlpha = 1f
        delay(1200)
        splashAlpha = 0f
        delay(800)
        showSplash = false
        isLoading = true
        loadingMessage = "Authenticating..."

        if (!hasTriedAuth) {
            hasTriedAuth = true
            coroutineScope.launch {
                try {
                    val authSuccess = showBiometricPrompt(activity)
                    if (!authSuccess) {
                        snackbarHostState.showSnackbar("Authentication failed.")
                        isLoading = false
                        return@launch
                    }

                    val prefs = getEncryptedPrefs(activity.applicationContext, activity)
                    prefs.edit { putBoolean("device_unlocked", true) }

                    // Initialize both services with activity context for encrypted prefs access
                    wiaService.initWithActivity(activity)
                    wuaService.initWithActivity(activity)

                    // WIA first (EUDI pattern): Wallet Instance Attestation proves wallet identity to Auth Server
                    val existingWia = wiaService.getStoredWia()
                    if (existingWia == null) {
                        // Request WIA (Wallet Instance Attestation)
                        loadingMessage = "Obtaining wallet attestation..."
                        Log.d(TAG, "No valid WIA found, initiating WIA issuance")

                        val wiaResult = withContext(Dispatchers.IO) {
                            wiaService.issueWia()
                        }

                        if (wiaResult.isSuccess) {
                            val wiaResponse = wiaResult.getOrNull()!!
                            Log.d(TAG, "WIA issued successfully. WIA ID: ${wiaResponse.wiaId}")
                        } else {
                            val error = wiaResult.exceptionOrNull()?.message ?: "Unknown error"
                            Log.e(TAG, "WIA issuance failed: $error")
                            // Continue anyway - WIA is optional for this PoC
                        }
                    } else {
                        Log.d(TAG, "Valid WIA already exists, skipping issuance")
                    }

                    // WUA second (EUDI pattern): Wallet Unit Attestation proves key security to Issuer
                    val existingWua = wuaService.getStoredWua()
                    if (existingWua == null) {
                        // First time activation - request WUA
                        loadingMessage = "Activating wallet..."
                        Log.d(TAG, "No valid WUA found, initiating WUA issuance")

                        val wuaResult = withContext(Dispatchers.IO) {
                            wuaService.issueWua()
                        }

                        if (wuaResult.isSuccess) {
                            val wuaResponse = wuaResult.getOrNull()!!
                            Log.d(TAG, "WUA issued successfully. WUA ID: ${wuaResponse.wuaId}")
                            snackbarHostState.showSnackbar("Wallet activated successfully")
                        } else {
                            val error = wuaResult.exceptionOrNull()?.message ?: "Unknown error"
                            Log.e(TAG, "WUA issuance failed: $error")
                            // Continue anyway - WUA is not blocking for this PoC
                            snackbarHostState.showSnackbar("Wallet activation pending: $error")
                        }
                    } else {
                        Log.d(TAG, "Valid WUA already exists, skipping issuance")
                    }

                    viewModel.authenticateSuccess() // Will trigger nav via LaunchedEffect
                } catch (e: Exception) {
                    Log.e(TAG, "Error during login/activation", e)
                    snackbarHostState.showSnackbar("Error: ${e.message}")
                    isLoading = false
                }
            }
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            if (showSplash) {
                Image(
                    painter = painterResource(id = R.drawable.logo),
                    contentDescription = "Splash Logo",
                    modifier = Modifier
                        .size(160.dp)
                        .alpha(animatedAlpha)
                )
            } else if (isLoading) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.semantics {
                            contentDescription = loadingMessage
                        }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = loadingMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}