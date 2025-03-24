package com.example.eudiwemu.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.example.eudiwemu.config.AppConfig
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

class WalletKeyManager {

    init {
        generateKeyPairIfNeeded()
    }

    // Generate key pair if it doesn't exist
    private fun generateKeyPairIfNeeded() {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!keyStore.containsAlias(AppConfig.KEY_ALIAS)) {
            val keyGen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            keyGen.initialize(
                KeyGenParameterSpec.Builder(AppConfig.KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
            keyGen.generateKeyPair()
        }
    }

    // Retrieve the wallet key from the Android Keystore
    fun getWalletKey(): ECKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val entry = keyStore.getEntry(AppConfig.KEY_ALIAS, null) as KeyStore.PrivateKeyEntry

        val publicKey = entry.certificate.publicKey as ECPublicKey

        return ECKey.Builder(Curve.P_256, publicKey)
            .keyID(AppConfig.KEY_ALIAS)
            .algorithm(JWSAlgorithm.ES256)
            .build()
    }

    // Retrieve the private key (if needed for signing)
    fun getPrivateKey(): PrivateKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val entry = keyStore.getEntry(AppConfig.KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
        return entry.privateKey
    }

}