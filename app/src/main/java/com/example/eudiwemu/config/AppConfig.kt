package com.example.eudiwemu.config

import com.example.eudiwemu.BuildConfig

class AppConfig {

    companion object {
        const val CLIENT_ID = "wallet-client"
        const val CLIENT_SECRET = "wallet-secret"
        const val SCOPE = "VerifiablePortableDocumentA1"
        const val REDIRECT_URI = "myapp://callback"
        const val KEY_ALIAS = "wallet-key"
        const val WUA_KEY_ALIAS = "wua-key"

        const val AUTH_SERVER_HOST = BuildConfig.AUTH_SERVER_HOST
        const val AUTH_SERVER_TOKEN_URL: String = BuildConfig.AUTH_SERVER_TOKEN_URL
        const val ISSUER_URL: String = BuildConfig.ISSUER_URL
        const val WALLET_PROVIDER_URL: String = BuildConfig.WALLET_PROVIDER_URL
    }

}
