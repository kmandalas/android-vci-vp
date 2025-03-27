package com.example.eudiwemu.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.UserNotAuthenticatedException
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

fun showBiometricPrompt(activity: FragmentActivity, onAuthResult: (Boolean) -> Unit) {
    val executor = ContextCompat.getMainExecutor(activity)
    val biometricPrompt = BiometricPrompt(
        activity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onAuthResult(true)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onAuthResult(false)
            }

            override fun onAuthenticationFailed() {
                onAuthResult(false)
            }
        })

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

fun getEncryptedPrefs(
    context: Context,
    activity: FragmentActivity,
    onSuccess: (SharedPreferences) -> Unit,
    onFailure: (Exception) -> Unit
) {
    try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .setUserAuthenticationRequired(true, 20) // Authentication required after 20 seconds
            .build()

        val prefs = EncryptedSharedPreferences.create(
            context,
            "wallet_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        // Return prefs on success
        onSuccess(prefs)

    } catch (e: UserNotAuthenticatedException) {
        // If authentication fails or expired, ask for biometric re-authentication
        showBiometricPrompt(activity) { success ->
            if (success) {
                // Retry after successful authentication
                getEncryptedPrefs(context, activity, onSuccess, onFailure)
            } else {
                // Notify failure if the user couldn't authenticate
                onFailure(e)
            }
        }

    } catch (e: Exception) {
        // Handle other exceptions (e.g., crypto errors, file access)
        onFailure(e)
    }
}