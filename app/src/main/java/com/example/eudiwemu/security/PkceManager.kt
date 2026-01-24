package com.example.eudiwemu.security

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import java.security.MessageDigest
import java.security.SecureRandom

object PkceManager {

    private const val PREF_NAME = "pkce_prefs"
    private const val KEY_CODE_VERIFIER = "pkce_code_verifier"

    /**
     * Generate and store a new PKCE code verifier and code challenge.
     * Returns the code challenge to use in the Authorization URL.
     */
    fun generateAndStoreCodeChallenge(context: Context): String {
        val codeVerifier = generateCodeVerifier()
        saveCodeVerifier(context, codeVerifier)
        return generateCodeChallenge(codeVerifier)
    }

    /**
     * Get the previously stored code verifier (used for token exchange).
     */
    fun getCodeVerifier(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CODE_VERIFIER, null)
            ?: throw IllegalStateException("Code verifier not found. You must start Authorization first.")
    }

    private fun saveCodeVerifier(context: Context, codeVerifier: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit { putString(KEY_CODE_VERIFIER, codeVerifier) }
    }

    private fun generateCodeVerifier(): String {
        val secureRandom = SecureRandom()
        val code = ByteArray(32)
        secureRandom.nextBytes(code)
        return Base64.encodeToString(code, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            .replace("=", "")
    }

    private fun generateCodeChallenge(codeVerifier: String): String {
        val bytes = codeVerifier.toByteArray(Charsets.US_ASCII)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            .replace("=", "")
    }

}