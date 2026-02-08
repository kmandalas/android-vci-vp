package com.example.eudiwemu.config

import com.example.eudiwemu.BuildConfig

class AppConfig {

    companion object {
        const val CLIENT_ID = "wallet-client"
        const val CLIENT_SECRET = "wallet-secret"
        const val SCOPE = "eu.europa.ec.eudi.pda1.1"
        const val REDIRECT_URI = "myapp://callback"
        const val KEY_ALIAS = "wallet-key"
        const val WUA_KEY_ALIAS = "wua-key"

        // Credential format constants
        const val FORMAT_SD_JWT = "dc+sd-jwt"
        const val FORMAT_MSO_MDOC = "mso_mdoc"

        // Credential configuration IDs (format-specific, used in credential requests and metadata lookups)
        const val SD_JWT_CREDENTIAL_CONFIG_ID = "eu.europa.ec.eudi.pda1_sd_jwt_vc"
        const val MDOC_CREDENTIAL_CONFIG_ID = "eu.europa.ec.eudi.pda1_mso_mdoc"
        const val MDOC_DOC_TYPE = "eu.europa.ec.eudi.pda1.1"

        const val AUTH_SERVER_HOST = BuildConfig.AUTH_SERVER_HOST
        const val AUTH_SERVER_TOKEN_URL: String = BuildConfig.AUTH_SERVER_TOKEN_URL
        const val ISSUER_URL: String = BuildConfig.ISSUER_URL
        const val WALLET_PROVIDER_URL: String = BuildConfig.WALLET_PROVIDER_URL

        // WIA (Wallet Instance Attestation) endpoints and configuration
        val WALLET_PROVIDER_WIA_NONCE_URL = "$WALLET_PROVIDER_URL/wia/nonce"
        val WALLET_PROVIDER_WIA_CREDENTIAL_URL = "$WALLET_PROVIDER_URL/wia/credential"

        // Auth server issuer (derived from token URL, used as audience for WIA PoP)
        val AUTH_SERVER_ISSUER: String = AUTH_SERVER_TOKEN_URL.substringBeforeLast("/oauth2/token")

        // PAR endpoint (derived from token URL)
        val AUTH_SERVER_PAR_URL: String = AUTH_SERVER_TOKEN_URL.replace("/oauth2/token", "/oauth2/par")

        // Storage keys for encrypted SharedPreferences
        const val STORED_WIA = "stored_wia"
        const val WIA_ID = "wia_id"
        const val STORED_WUA = "stored_wua"
        const val WUA_ID = "wua_id"

        // Credential storage - keyed by credential type for multi-credential support
        private const val STORED_VC_PREFIX = "stored_vc_"
        private const val FORMAT_SUFFIX = "_format"

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
         * E.g., "eu.europa.ec.eudi.pda1_mso_mdoc" -> "pda1"
         */
        fun extractCredentialType(scope: String): String {
            // Match config ID format: eu.europa.ec.eudi.<type>_sd_jwt_vc or _mso_mdoc
            val configRegex = Regex("""eu\.europa\.ec\.eudi\.(\w+)_(?:sd_jwt_vc|mso_mdoc)""")
            configRegex.find(scope)?.groupValues?.get(1)?.let { return it }
            // Match scope format: eu.europa.ec.eudi.<type>.<version>
            val scopeRegex = Regex("""eu\.europa\.ec\.eudi\.(\w+)\.\d+""")
            scopeRegex.find(scope)?.groupValues?.get(1)?.let { return it }
            return scope
        }

        /**
         * Get storage key for the credential format.
         * E.g., "pda1" -> "stored_vc_pda1_format"
         */
        fun getCredentialFormatStorageKey(credentialType: String): String {
            return "${STORED_VC_PREFIX}${credentialType}$FORMAT_SUFFIX"
        }

        /**
         * Get storage key for claims metadata of a credential type.
         * E.g., "pda1" -> "stored_vc_pda1_claims_metadata"
         */
        fun getClaimsMetadataStorageKey(credentialType: String): String {
            return "${STORED_VC_PREFIX}${credentialType}_claims_metadata"
        }

        /**
         * Get storage key for credential display metadata of a credential type.
         * E.g., "pda1" -> "stored_vc_pda1_display"
         */
        fun getCredentialDisplayStorageKey(credentialType: String): String {
            return "${STORED_VC_PREFIX}${credentialType}_display"
        }

        // Default PDA1 credential storage key (for convenience)
        val STORED_VC_PDA1: String get() = getCredentialStorageKey(extractCredentialType(SCOPE))
    }

}
