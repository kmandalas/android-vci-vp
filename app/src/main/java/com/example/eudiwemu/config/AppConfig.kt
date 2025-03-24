package com.example.eudiwemu.config

class AppConfig {

    companion object {
        const val AUTH_SERVER_URL = "http://192.168.1.65:9000/oauth2/token"
        const val ISSUER_URL = "http://192.168.1.65:8080"
        const val CLIENT_ID = "wallet-client"
        const val CLIENT_SECRET = "wallet-secret"
        const val KEY_ALIAS = "wallet-key"
    }

}