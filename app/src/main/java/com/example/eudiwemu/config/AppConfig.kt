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

        // Credential storage - keyed by credential type + format for multi-credential support
        private const val STORED_VC_PREFIX = "stored_vc_"
        const val STORED_CREDENTIAL_INDEX = "stored_credential_index"

        /**
         * Extract format suffix from a credential configuration ID.
         * E.g., "eu.europa.ec.eudi.pda1_sd_jwt_vc" -> "sdjwt"
         * E.g., "eu.europa.ec.eudi.pda1_mso_mdoc" -> "mdoc"
         */
        fun extractFormatSuffix(configId: String): String {
            return when {
                configId.endsWith("_mso_mdoc") -> "mdoc"
                else -> "sdjwt"
            }
        }

        /**
         * Extract composite credential key from a configuration ID.
         * Combines credential type and format suffix for unique storage keys.
         * E.g., "eu.europa.ec.eudi.pda1_sd_jwt_vc" -> "pda1_sdjwt"
         * E.g., "eu.europa.ec.eudi.pda1_mso_mdoc" -> "pda1_mdoc"
         */
        fun extractCredentialKey(configId: String): String {
            return "${extractCredentialType(configId)}_${extractFormatSuffix(configId)}"
        }

        /**
         * Get storage key for a credential bundle.
         * E.g., "pda1_sdjwt" -> "stored_vc_pda1_sdjwt"
         */
        fun getCredentialStorageKey(credentialKey: String): String {
            return "$STORED_VC_PREFIX$credentialKey"
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
    }

}
