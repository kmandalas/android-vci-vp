package com.example.eudiwemu.ui

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavController
import com.example.eudiwemu.R
import com.example.eudiwemu.security.getEncryptedPrefs
import com.example.eudiwemu.security.showBiometricPrompt
import com.example.eudiwemu.ui.viewmodel.AuthenticationViewModel
import kotlinx.coroutines.delay
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

    var isLoading by remember { mutableStateOf(false) }
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
            if (showSplash) {
                Image(
                    painter = painterResource(id = R.drawable.logo),
                    contentDescription = "Splash Logo",
                    modifier = Modifier
                        .size(160.dp)
                        .alpha(animatedAlpha)
                )
            } else if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.semantics {
                        contentDescription = "Authenticating..."
                    }
                )
            }
        }
    }
}