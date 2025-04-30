package com.example.eudiwemu.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    var isLoading by remember { mutableStateOf(true) } // Start in loading state
    var hasTriedAuth by remember { mutableStateOf(false) } // Prevent re-auth loop

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
        if (!hasTriedAuth) {
            hasTriedAuth = true
            coroutineScope.launch {
                try {
                    val authSuccess = showBiometricPrompt(activity)
                    if (!authSuccess) {
                        snackbarHostState.showSnackbar("❌ Authentication failed.")
                        isLoading = false
                        return@launch
                    }

                    val prefs = getEncryptedPrefs(activity.applicationContext, activity)
                    prefs.edit().putBoolean("device_unlocked", true).apply()

                    viewModel.authenticateSuccess() // Will trigger nav via LaunchedEffect
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("❌ Error: ${e.message}")
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
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.semantics {
                    contentDescription = "Authenticating..."
                })
            }
        }
    }
}
