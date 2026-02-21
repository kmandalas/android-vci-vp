package com.example.eudiwemu

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.eudiwemu.security.getEncryptedPrefs
import com.example.eudiwemu.service.WiaService
import com.example.eudiwemu.service.WuaService
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.example.eudiwemu.ui.CredentialDetailScreen
import com.example.eudiwemu.ui.LoginScreen
import com.example.eudiwemu.ui.WalletScreen
import com.example.eudiwemu.ui.theme.EUDIWEMUTheme
import com.example.eudiwemu.ui.viewmodel.WalletViewModel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.compose.koinViewModel

class MainActivity : FragmentActivity() {
    private val wuaService: WuaService by inject()
    private val wiaService: WiaService by inject()

    private val isAuthenticated = mutableStateOf(false)
    val currentIntent = mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentIntent.value = intent

        lifecycleScope.launch {
            try {
                val prefs = getEncryptedPrefs(this@MainActivity.applicationContext, this@MainActivity)
                val deviceUnlocked = prefs.getBoolean("device_unlocked", false)

                // Initialize WiaService with activity context for encrypted prefs access
                wiaService.initWithActivity(this@MainActivity)

                isAuthenticated.value = deviceUnlocked
            } catch (e: Exception) {
                // KeyStoreException with "unusable" master key is expected when
                // auth timeout expires - Tink will retry after biometric prompt
                val isExpectedAuthTimeout = e is java.security.KeyStoreException &&
                    e.message?.contains("unusable") == true

                if (isExpectedAuthTimeout) {
                    Log.d("MainActivity", "Master key requires re-authentication, biometric prompt will appear")
                } else {
                    Log.e("MainActivity", "Failed to get encrypted prefs", e)
                }
                isAuthenticated.value = false
            }
            setContent {
                EUDIWEMUTheme(
                    darkTheme = false,
                    dynamicColor = false
                ) {
                    MainNavHost(
                        activity = this@MainActivity,
                        intent = currentIntent.value,
                        wuaService = wuaService,
                        wiaService = wiaService,
                        isAuthenticated = isAuthenticated.value,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        currentIntent.value = intent
    }
}

@Composable
fun MainNavHost(
    activity: FragmentActivity,
    intent: Intent?,
    wuaService: WuaService,
    wiaService: WiaService,
    isAuthenticated: Boolean
) {
    val navController = rememberNavController()
    val startDestination = remember(isAuthenticated) {
        if (isAuthenticated) "wallet_app_screen" else "login_screen"
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable("login_screen") {
            LoginScreen(
                activity = activity,
                navController = navController,
                wuaService = wuaService,
                wiaService = wiaService
            )
        }
        composable("wallet_app_screen") {
            val walletViewModel: WalletViewModel = koinViewModel(viewModelStoreOwner = activity)
            WalletScreen(
                activity = activity,
                intent = intent,
                viewModel = walletViewModel,
                navController = navController
            )
        }
        composable(
            "credential_detail/{credentialKey}",
            arguments = listOf(navArgument("credentialKey") { type = NavType.StringType })
        ) { backStackEntry ->
            val credentialKey = backStackEntry.arguments?.getString("credentialKey") ?: return@composable
            val walletViewModel: WalletViewModel = koinViewModel(viewModelStoreOwner = activity)
            CredentialDetailScreen(
                credentialKey = credentialKey,
                viewModel = walletViewModel,
                activity = activity,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
