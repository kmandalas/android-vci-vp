package com.example.eudiwemu.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavController
import com.example.eudiwemu.AuthenticationViewModel
import com.example.eudiwemu.security.getEncryptedPrefs
import com.example.eudiwemu.security.showBiometricPrompt
import com.example.eudiwemu.service.IssuanceService
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    activity: FragmentActivity,
    viewModel: AuthenticationViewModel,
    navController: NavController,
    issuanceService: IssuanceService
) {
    val isAuthenticated by viewModel.isAuthenticated

    // Automatically navigate to wallet if already authenticated
    LaunchedEffect(isAuthenticated) {
        if (isAuthenticated) {
            navController.navigate("wallet_app_screen") {
                popUpTo("login_screen") { inclusive = true }
            }
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            Button(onClick = {
                showBiometricPrompt(activity) { success ->
                    if (success) {
                        coroutineScope.launch {
                            try {
                                // Always request a new access token
                                val accessToken = issuanceService.obtainAccessToken() // Get new token

                                // Pass all required parameters for getEncryptedPrefs
                                getEncryptedPrefs(
                                    context = activity.applicationContext, // Passing context
                                    activity = activity, // Passing activity
                                    onSuccess = { prefs ->
                                        prefs.edit()
                                            .putString("access_token", accessToken.access_token)
                                            .apply()

                                        // Navigate to WalletApp
                                        navController.navigate("wallet_app_screen") {
                                            popUpTo("login_screen") { inclusive = true }
                                        }
                                    },
                                    onFailure = { e ->
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar("❌ Failed to get access token: ${e.message}")
                                        }
                                    }
                                )
                            } catch (e: Exception) {
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar("❌ Failed to get access token: ${e.message}")
                                }
                            }
                        }
                    } else {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("❌ Authentication failed.")
                        }
                    }
                }
            }) {
                Text("Login with Biometrics")
            }
        }
    }
}




