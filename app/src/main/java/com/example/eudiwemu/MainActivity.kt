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
import com.example.eudiwemu.security.PkceManager
import com.example.eudiwemu.security.getEncryptedPrefs
import com.example.eudiwemu.service.IssuanceService
import com.example.eudiwemu.service.VpTokenService
import com.example.eudiwemu.ui.LoginScreen
import com.example.eudiwemu.ui.WalletScreen
import com.example.eudiwemu.ui.viewmodel.AuthenticationViewModel
import com.nimbusds.jwt.JWTParser
import kotlinx.coroutines.launch
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        intent.data?.let { uri ->
            val code = uri.getQueryParameter("code")
            if (code != null) {
                Log.d("OAuth", "Authorization code: $code")

                lifecycleScope.launch {
                    val codeVerifier = PkceManager.getCodeVerifier(this@MainActivity.applicationContext)
                    val result = issuanceService.exchangeAuthorizationCodeForToken(code, codeVerifier)

                    if (result.isSuccess) {
                        val accessToken = result.getOrNull()

                        // Save the access token securely
                        val prefs = getEncryptedPrefs(this@MainActivity.applicationContext, this@MainActivity)
                        prefs.edit().putString("access_token", accessToken).apply()

                        Log.d("OAuth", "Access token saved!")

                        // Now you can trigger `requestVC` directly if you want!
                    } else {
                        Log.e("OAuth", "Failed to get access token: ${result.exceptionOrNull()?.message}")
                    }
                }
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