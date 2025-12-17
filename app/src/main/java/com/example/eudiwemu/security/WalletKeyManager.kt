package com.example.eudiwemu.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import android.util.Log
import com.example.eudiwemu.config.AppConfig
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

class WalletKeyManager {

    companion object {
        private const val TAG = "WalletKeyManager"
    }

    init {
        generateKeyPairIfNeeded()
    }

    // Generate key pair if it doesn't exist
    private fun generateKeyPairIfNeeded() {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!keyStore.containsAlias(AppConfig.KEY_ALIAS)) {
            val keyGen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")

            // Try StrongBox first, fall back to TEE
            try {
                keyGen.initialize(
                    KeyGenParameterSpec.Builder(AppConfig.KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setUserAuthenticationRequired(false)
                        .setIsStrongBoxBacked(true)
                        .build()
                )
                keyGen.generateKeyPair()
                Log.d(TAG, "Wallet key generated with StrongBox")
            } catch (e: StrongBoxUnavailableException) {
                Log.d(TAG, "StrongBox not available, falling back to TEE")
                keyGen.initialize(
                    KeyGenParameterSpec.Builder(AppConfig.KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setUserAuthenticationRequired(false)
                        .build()
                )
                keyGen.generateKeyPair()
                Log.d(TAG, "Wallet key generated with TEE")
            }
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

    // ==================== WUA Key Management ====================

    /**
     * Generate WUA key pair with attestation challenge.
     * This key includes Android Key Attestation which proves the key
     * is protected by hardware (TEE/StrongBox).
     *
     * @param attestationChallenge The challenge bytes from the Wallet Provider nonce
     * @return true if key was generated, false if it already exists
     */
    fun generateWuaKeyWithAttestation(attestationChallenge: ByteArray): Boolean {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        // Delete existing WUA key to regenerate with new attestation challenge
        if (keyStore.containsAlias(AppConfig.WUA_KEY_ALIAS)) {
            Log.d(TAG, "WUA key already exists, deleting for re-attestation")
            keyStore.deleteEntry(AppConfig.WUA_KEY_ALIAS)
        }

        Log.d(TAG, "Generating WUA key with attestation challenge")
        val keyGen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")

        // Try StrongBox first, fall back to TEE
        try {
            keyGen.initialize(
                KeyGenParameterSpec.Builder(AppConfig.WUA_KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .setAttestationChallenge(attestationChallenge)
                    .setIsStrongBoxBacked(true)
                    .build()
            )
            keyGen.generateKeyPair()
            Log.d(TAG, "WUA key generated with StrongBox")
        } catch (e: StrongBoxUnavailableException) {
            Log.d(TAG, "StrongBox not available for WUA key, falling back to TEE")
            keyGen.initialize(
                KeyGenParameterSpec.Builder(AppConfig.WUA_KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .setAttestationChallenge(attestationChallenge)
                    .build()
            )
            keyGen.generateKeyPair()
            Log.d(TAG, "WUA key generated with TEE")
        }

        return true
    }

    /**
     * Check if WUA key exists in the keystore
     */
    fun hasWuaKey(): Boolean {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return keyStore.containsAlias(AppConfig.WUA_KEY_ALIAS)
    }

    /**
     * Retrieve the WUA public key as ECKey (JWK format)
     */
    fun getWuaKey(): ECKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val entry = keyStore.getEntry(AppConfig.WUA_KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
        val publicKey = entry.certificate.publicKey as ECPublicKey

        return ECKey.Builder(Curve.P_256, publicKey)
            .keyID(AppConfig.WUA_KEY_ALIAS)
            .algorithm(JWSAlgorithm.ES256)
            .build()
    }

    /**
     * Retrieve the WUA private key for signing operations
     */
    fun getWuaPrivateKey(): PrivateKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val entry = keyStore.getEntry(AppConfig.WUA_KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
        return entry.privateKey
    }

    /**
     * Get the Android Key Attestation certificate chain for the WUA key.
     * This chain proves the key is hardware-backed and includes:
     * - Leaf certificate: Contains the public key and attestation extension
     * - Intermediate certificates: Chain to Google's root CA
     *
     * @return List of Base64-encoded X.509 certificates (leaf first)
     */
    fun getWuaAttestationCertificateChain(): List<String> {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        if (!keyStore.containsAlias(AppConfig.WUA_KEY_ALIAS)) {
            throw IllegalStateException("WUA key not found. Call generateWuaKeyWithAttestation first.")
        }

        val certChain: Array<Certificate> = keyStore.getCertificateChain(AppConfig.WUA_KEY_ALIAS)
            ?: throw IllegalStateException("No certificate chain found for WUA key")

        Log.d(TAG, "Retrieved attestation certificate chain with ${certChain.size} certificates")

        return certChain.map { cert ->
            Base64.encodeToString(cert.encoded, Base64.NO_WRAP)
        }
    }

}