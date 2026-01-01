package com.example.eudiwemu.security

import android.util.Base64
import android.util.Log
import com.example.eudiwemu.config.AppConfig
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.security.MessageDigest
import java.util.Date
import java.util.UUID

/**
 * Manages DPoP (Demonstrating Proof-of-Possession) proofs per RFC 9449.
 * Reuses the wallet key - no separate DPoP key needed.
 */
class DPoPManager(private val walletKeyManager: WalletKeyManager) {

    companion object {
        private const val TAG = "DPoPManager"
    }

    /**
     * Creates a DPoP proof JWT for a given HTTP request.
     *
     * @param httpMethod The HTTP method (e.g., "POST", "GET")
     * @param httpUri The target URI (e.g., "http://192.168.1.65:9000/oauth2/token")
     * @param nonce Optional server-provided nonce (from DPoP-Nonce header)
     * @param accessTokenHash Optional hash of the access token (ath claim)
     * @return Serialized DPoP proof JWT
     */
    fun createDPoPProof(
        httpMethod: String,
        httpUri: String,
        nonce: String? = null,
        accessTokenHash: String? = null
    ): String {
        val walletKey = walletKeyManager.getWalletKey()

        val header = JWSHeader.Builder(JWSAlgorithm.ES256)
            .type(JOSEObjectType("dpop+jwt"))
            .jwk(walletKey.toPublicJWK())
            .build()

        val claimsBuilder = JWTClaimsSet.Builder()
            .jwtID(UUID.randomUUID().toString())
            .claim("htm", httpMethod)
            .claim("htu", httpUri)
            .issueTime(Date())

        nonce?.let { claimsBuilder.claim("nonce", it) }
        accessTokenHash?.let { claimsBuilder.claim("ath", it) }

        val signedJWT = SignedJWT(header, claimsBuilder.build())

        // Sign with wallet key via Android Keystore
        val signer = AndroidKeystoreSigner(AppConfig.KEY_ALIAS)
        signedJWT.sign(signer)

        Log.d(TAG, "Created DPoP proof for $httpMethod $httpUri")
        return signedJWT.serialize()
    }

    /**
     * Compute the access token hash (ath) for binding DPoP to a specific token.
     * Uses SHA-256 and base64url encoding per RFC 9449.
     */
    fun computeAccessTokenHash(accessToken: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(accessToken.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(
            hash,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
    }
}
