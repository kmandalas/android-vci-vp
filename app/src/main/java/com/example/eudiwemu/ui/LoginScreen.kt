package com.example.eudiwemu.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
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
import androidx.navigation.NavController
import com.example.eudiwemu.security.getEncryptedPrefs
import com.example.eudiwemu.security.showBiometricPrompt
import com.example.eudiwemu.ui.viewmodel.AuthenticationViewModel
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    activity: FragmentActivity,
    viewModel: AuthenticationViewModel,
    navController: NavController
) {
    val isAuthenticated by viewModel.isAuthenticated
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) } // State for loading indicator

    // Automatically navigate if already authenticated
    LaunchedEffect(isAuthenticated) {
        if (isAuthenticated) {
            // You can navigate with animation or delay for smoother transition
            navController.navigate("wallet_app_screen") {
                popUpTo("login_screen") { inclusive = true }
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
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(modifier = Modifier.height(16.dp))

                // Loading indicator conditionally
                if (isLoading) {
                    CircularProgressIndicator() // Show loading spinner
                } else {
                    // Login Button
                    Button(onClick = {
                        // Start loading when login attempt is made
                        isLoading = true
                        coroutineScope.launch {
                            try {
                                // Attempt to authenticate with biometrics
                                val authSuccess = showBiometricPrompt(activity)
                                if (!authSuccess) {
                                    snackbarHostState.showSnackbar("❌ Authentication failed.")
                                    isLoading = false
                                    return@launch
                                }

                                // Get encrypted preferences and save action
                                val prefs = getEncryptedPrefs(activity.applicationContext, activity)
                                prefs.edit()
                                    .putBoolean("device_unlocked", true)
                                    .apply()

                                viewModel.authenticateSuccess() // Update authentication state

                                // Navigate to WalletApp screen with smooth transition
                                navController.navigate("wallet_app_screen") {
                                    popUpTo("login_screen") { inclusive = true }
                                }
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar("❌ Error: ${e.message}")
                                isLoading = false
                            }
                        }
                    }) {
                        Text("Login with Biometrics")
                    }
                }
            }
        }
    }
}