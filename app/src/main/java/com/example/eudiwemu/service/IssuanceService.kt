package com.example.eudiwemu.service

import android.util.Base64
import android.util.Log
import com.example.eudiwemu.config.AppConfig
import com.example.eudiwemu.dto.AccessTokenResponse
import com.example.eudiwemu.dto.NonceResponse
import com.example.eudiwemu.security.AndroidKeystoreSigner
import com.example.eudiwemu.security.WalletKeyManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.basicAuth
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.util.Date

class IssuanceService(
    private val client: HttpClient,
    private val walletKeyManager: WalletKeyManager
) {

    suspend fun obtainAccessToken(): AccessTokenResponse {
        val response: AccessTokenResponse = client.post(AppConfig.AUTH_SERVER_URL) {
            contentType(ContentType.Application.FormUrlEncoded)
            basicAuth(AppConfig.CLIENT_ID, AppConfig.CLIENT_SECRET)
            setBody("grant_type=client_credentials&scope=openid vc:issue")
        }.body()

        return response
    }

    suspend fun getNonce(accessToken: String): String {
        val response: NonceResponse = client.get("${AppConfig.ISSUER_URL}/credential/nonce") {
            bearerAuth(accessToken)
        }.body()

        return response.c_nonce
    }

    fun createJwtProof(nonce: String): String {
        val ecKey = walletKeyManager.getWalletKey()
        val header = JWSHeader.Builder(JWSAlgorithm.ES256)
            .type(JOSEObjectType("openid4vci-proof+jwt"))
            .jwk(ecKey.toPublicJWK())
            .build()

        val claims = JWTClaimsSet.Builder()
            .issuer("wallet-client")
            .audience(AppConfig.ISSUER_URL)
            .issueTime(Date())
            .expirationTime(Date(System.currentTimeMillis() + 300000))
            .claim("nonce", nonce)
            .build()

        val signedJWT = SignedJWT(header, claims)
        // val signer = ECDSASigner(ecKey.toECPrivateKey())
        val signer = AndroidKeystoreSigner(AppConfig.KEY_ALIAS) // ✅ Uses Android Keystore API
        signedJWT.sign(signer)

        return signedJWT.serialize()
    }

    suspend fun requestCredential(accessToken: String, jwtProof: String): String {
        val requestBody = """
            {
              "format": "vc+sd-jwt",
              "credentialConfigurationId": "IdentityCredential",
              "proof": {
                "proofType": "jwt",
                "jwt": "$jwtProof"
              }
            }
        """.trimIndent()

        val response: HttpResponse = client.post("${AppConfig.ISSUER_URL}/credential") {
            contentType(ContentType.Application.Json)
            bearerAuth(accessToken)
            setBody(requestBody)
        }
        val jsonResponse: Map<String, String> = response.body()
        verifyCredential(jsonResponse["credential"].orEmpty())
        return jsonResponse["credential"] ?: throw Exception("Failed to request credential")
    }

    private suspend fun verifyCredential(sdJwt: String) {
        Log.d("WalletApp", "Verifying SD-JWT...")
        if (sdJwt.isEmpty()) throw RuntimeException("No SD-JWT included!")

        // Step 1: Split SD-JWT into JWT part and disclosures
        val parts = sdJwt.split("~")
        val jwtPart = parts.firstOrNull() ?: throw IllegalArgumentException("Invalid SD-JWT format")
        val disclosures = parts.drop(1).filter { it.isNotEmpty() }

        Log.d("WalletApp", "Parsed JWT: $jwtPart")
        Log.d("WalletApp", "Number of disclosures: ${disclosures.size}")

        // Step 2: Fetch the issuer's public key (JWKS)
        val jwkSet = fetchIssuerJWKSet("${AppConfig.ISSUER_URL}/.well-known/jwks.json")

        // Step 3: Verify the SD-JWT signature
        val signedJwt = SignedJWT.parse(jwtPart)
        val publicKey = jwkSet.keys.first().toECKey()
        val verifier = ECDSAVerifier(publicKey)

        val isValid = signedJwt.verify(verifier)
        Log.d("WalletApp", "Signature valid: $isValid")

        if (!isValid) throw RuntimeException("Invalid SD-JWT signature!")
    }

    fun decodeCredential(sdJwt: String): Map<String, String> {
        Log.d("WalletApp", "Decoding SD-JWT...")

        val gson = Gson()

        // Step 1: Split SD-JWT into JWT part and disclosures
        val parts = sdJwt.split("~")
        val disclosures = parts.drop(1).filter { it.isNotEmpty() }

        Log.d("WalletApp", "Number of disclosures: ${disclosures.size}")

        // Step 2: Decode and reveal disclosed claims
        val decodedClaims = mutableMapOf<String, String>()

        for (disclosure in disclosures) {
            try {
                val decodedBytes = Base64.decode(disclosure, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                val decodedJson = String(decodedBytes)
                val claimData: List<String> = gson.fromJson(decodedJson, object : TypeToken<List<String>>() {}.type)

                if (claimData.size >= 3) {
                    val claimName = claimData[1]
                    val claimValue = claimData[2]
                    decodedClaims[claimName] = claimValue
                    Log.d("WalletApp", "Decoded Claim: $claimName -> $claimValue")
                } else {
                    Log.d("WalletApp", "Malformed disclosure: $decodedJson")
                }
            } catch (e: Exception) {
                Log.e("WalletApp", "Error decoding disclosure: $disclosure", e)
            }
        }

        return decodedClaims
    }

    // Helper function to fetch issuer's JWK Set
    private suspend fun fetchIssuerJWKSet(jwksUrl: String): JWKSet {
        return try {
            val response: HttpResponse = client.get(jwksUrl)
            val jwksJson: String = response.body()
            JWKSet.parse(jwksJson)
        } catch (e: Exception) {
            throw RuntimeException("Failed to fetch JWK Set: ${e.message}")
        } finally {
            client.close()
        }
    }

}