package com.example.eudiwemu.config

import com.example.eudiwemu.BuildConfig

class AppConfig {

    companion object {
        const val CLIENT_ID = "wallet-client"
        const val CLIENT_SECRET = "wallet-secret"
        const val SCOPE = "eu.europa.ec.eudi.pda1_sd_jwt_vc"
        const val REDIRECT_URI = "myapp://callback"
        const val KEY_ALIAS = "wallet-key"
        const val WUA_KEY_ALIAS = "wua-key"

        const val AUTH_SERVER_HOST = BuildConfig.AUTH_SERVER_HOST
        const val AUTH_SERVER_TOKEN_URL: String = BuildConfig.AUTH_SERVER_TOKEN_URL
        const val ISSUER_URL: String = BuildConfig.ISSUER_URL
        const val WALLET_PROVIDER_URL: String = BuildConfig.WALLET_PROVIDER_URL

        // WIA (Wallet Instance Attestation) endpoints and configuration
        val WALLET_PROVIDER_WIA_NONCE_URL = "$WALLET_PROVIDER_URL/wia/nonce"
        val WALLET_PROVIDER_WIA_CREDENTIAL_URL = "$WALLET_PROVIDER_URL/wia/credential"

        // Auth server issuer (derived from token URL, used as audience for WIA PoP)
        val AUTH_SERVER_ISSUER: String = AUTH_SERVER_TOKEN_URL.substringBeforeLast("/oauth2/token")

        // Storage keys for encrypted SharedPreferences
        const val STORED_WIA = "stored_wia"
        const val WIA_ID = "wia_id"
        const val STORED_WUA = "stored_wua"
        const val WUA_ID = "wua_id"

        // Credential storage - keyed by credential type for multi-credential support
        private const val STORED_VC_PREFIX = "stored_vc_"

        /**
         * Get storage key for a credential type.
         * E.g., "pda1" -> "stored_vc_pda1"
         */
        fun getCredentialStorageKey(credentialType: String): String {
            return "$STORED_VC_PREFIX$credentialType"
        }

        /**
         * Extract credential type from scope/configuration ID.
         * E.g., "eu.europa.ec.eudi.pda1_sd_jwt_vc" -> "pda1"
         */
        fun extractCredentialType(scope: String): String {
            // Pattern: eu.europa.ec.eudi.<type>_sd_jwt_vc
            val regex = Regex("""eu\.europa\.ec\.eudi\.(\w+)_sd_jwt_vc""")
            return regex.find(scope)?.groupValues?.get(1) ?: scope
        }

        // Default PDA1 credential storage key (for convenience)
        val STORED_VC_PDA1: String get() = getCredentialStorageKey(extractCredentialType(SCOPE))
    }

}

