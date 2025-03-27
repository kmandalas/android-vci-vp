package com.example.eudiwemu.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.UserNotAuthenticatedException
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun showBiometricPrompt(activity: FragmentActivity): Boolean {
    return suspendCancellableCoroutine { continuation ->
        val executor = ContextCompat.getMainExecutor(activity)
        val biometricPrompt = BiometricPrompt(
            activity, executor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    continuation.resume(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    continuation.resume(false)
                }

                override fun onAuthenticationFailed() {
                    continuation.resume(false)
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Authenticate to Access Wallet")
            .setSubtitle("Use your fingerprint or device unlock")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        biometricPrompt.authenticate(promptInfo)
    }
}

suspend fun getEncryptedPrefs(context: Context, activity: FragmentActivity): SharedPreferences {
    return suspendCancellableCoroutine { continuation ->
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .setUserAuthenticationRequired(true, 20) // Require auth every 20 sec
                .build()

            val prefs = EncryptedSharedPreferences.create(
                context,
                "wallet_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            // Return prefs on success
            continuation.resume(prefs)

        } catch (e: UserNotAuthenticatedException) {
            // Prompt for biometric authentication if needed
            activity.lifecycleScope.launch {
                val isAuthenticated = showBiometricPrompt(activity)
                if (isAuthenticated) {
                    // Retry if authentication succeeds
                    continuation.resume(getEncryptedPrefs(context, activity))
                } else {
                    // Fail if user cancels authentication
                    continuation.resumeWithException(e)
                }
            }
        } catch (e: Exception) {
            // Handle other exceptions (e.g., crypto errors)
            continuation.resumeWithException(e)
        }
    }
}