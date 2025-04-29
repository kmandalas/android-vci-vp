package com.example.eudiwemu

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.eudiwemu.security.getEncryptedPrefs
import com.example.eudiwemu.service.IssuanceService
import com.example.eudiwemu.service.VpTokenService
import com.example.eudiwemu.ui.LoginScreen
import com.example.eudiwemu.ui.WalletScreen
import com.example.eudiwemu.ui.viewmodel.AuthenticationViewModel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class MainActivity : FragmentActivity() {
    private val issuanceService: IssuanceService by inject()
    private val vpTokenService: VpTokenService by inject()

    private val isAuthenticated = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            try {
                val prefs = getEncryptedPrefs(this@MainActivity.applicationContext, this@MainActivity)
                val deviceUnlocked = prefs.getBoolean("device_unlocked", false)

                isAuthenticated.value = deviceUnlocked
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
                navController = navController
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