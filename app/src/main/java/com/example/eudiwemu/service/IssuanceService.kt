package com.example.eudiwemu.service

import android.util.Base64
import android.util.Log
import com.example.eudiwemu.config.AppConfig
import com.example.eudiwemu.dto.AccessTokenResponse
import com.example.eudiwemu.dto.NonceResponse
import com.example.eudiwemu.security.AndroidKeystoreSigner
import com.example.eudiwemu.security.DPoPManager
import com.example.eudiwemu.security.WalletKeyManager
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.basicAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.util.Date

class IssuanceService(
    private val client: HttpClient,
    private val walletKeyManager: WalletKeyManager
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val dpopManager = DPoPManager(walletKeyManager)

    @Deprecated("replaced client_credentials with authorization_code flow")
    suspend fun obtainAccessToken(): AccessTokenResponse {
        val response: AccessTokenResponse = client.post(AppConfig.AUTH_SERVER_TOKEN_URL) {
            contentType(ContentType.Application.FormUrlEncoded)
            basicAuth(AppConfig.CLIENT_ID, AppConfig.CLIENT_SECRET)
            setBody("grant_type=client_credentials&scope=openid vc:issue")
        }.body()

        return response
    }

    suspend fun exchangeAuthorizationCodeForToken(
        code: String,
        codeVerifier: String,
        redirectUri: String = "myapp://callback"
    ): Result<String> {
        return try {
            val tokenUrl = AppConfig.AUTH_SERVER_TOKEN_URL

            val dpopProof = dpopManager.createDPoPProof(
                httpMethod = "POST",
                httpUri = tokenUrl
            )

            val response = client.submitForm(
                url = tokenUrl,
                formParameters = Parameters.build {
                    append("grant_type", "authorization_code")
                    append("code", code)
                    append("redirect_uri", redirectUri)
                    append("code_verifier", codeVerifier)
                    append("client_id", AppConfig.CLIENT_ID)
                }
            ) {
                header("DPoP", dpopProof)
            }

            val accessTokenResponse = response.body<AccessTokenResponse>()
            Log.d("IssuanceService", "Token type: ${accessTokenResponse.token_type}")
            Result.success(accessTokenResponse.access_token)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getNonce(accessToken: String): String {
        val nonceUrl = "${AppConfig.ISSUER_URL}/credential/nonce"

        val dpopProof = dpopManager.createDPoPProof(
            httpMethod = "GET",
            httpUri = nonceUrl,
            accessTokenHash = dpopManager.computeAccessTokenHash(accessToken)
        )

        val response: NonceResponse = client.get(nonceUrl) {
            header("Authorization", "DPoP $accessToken")
            header("DPoP", dpopProof)
        }.body()

        return response.c_nonce
    }

    /**
     * Create JWT proof for credential request.
     * Uses WUA key (attested) and includes key_attestation header with WUA JWT.
     *
     * @param nonce The nonce from the issuer
     * @param wuaJwt The Wallet Unit Attestation JWT (from encrypted SharedPreferences)
     * @return Serialized JWT proof string
     */
    fun createJwtProof(nonce: String, wuaJwt: String): String {
        Log.d("IssuanceService", "Creating JWT proof with WUA key_attestation")

        val header = JWSHeader.Builder(JWSAlgorithm.ES256)
            .type(JOSEObjectType("openid4vci-proof+jwt"))
            .keyID("0")  // Index into attested_keys in WUA
            .customParam("key_attestation", wuaJwt)
            .build()

        val claims = JWTClaimsSet.Builder()
            .issuer("wallet-client")
            .audience(AppConfig.ISSUER_URL)
            .issueTime(Date())
            .expirationTime(Date(System.currentTimeMillis() + 300000))
            .claim("nonce", nonce)
            .build()

        val signedJWT = SignedJWT(header, claims)
        val signer = AndroidKeystoreSigner(AppConfig.WUA_KEY_ALIAS)  // WUA key (attested)
        signedJWT.sign(signer)

        return signedJWT.serialize()
    }

    suspend fun requestCredential(accessToken: String, jwtProof: String): String {
        val credentialUrl = "${AppConfig.ISSUER_URL}/credential"

        val dpopProof = dpopManager.createDPoPProof(
            httpMethod = "POST",
            httpUri = credentialUrl,
            accessTokenHash = dpopManager.computeAccessTokenHash(accessToken)
        )

        val requestBody = """
            {
              "format": "dc+sd-jwt",
              "credentialConfigurationId": "${AppConfig.SCOPE}",
              "proof": {
                "proofType": "jwt",
                "jwt": "$jwtProof"
              }
            }
        """.trimIndent()

        val response: HttpResponse = client.post(credentialUrl) {
            contentType(ContentType.Application.Json)
            header("Authorization", "DPoP $accessToken")
            header("DPoP", dpopProof)
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

        val signedJwt = SignedJWT.parse(jwtPart)

        // Step 2: Fetch the issuer's public key (JWKS)
        val jwkSet = fetchIssuerJWKSet("${AppConfig.ISSUER_URL}/.well-known/jwks.json")
        val jwksKey = jwkSet.keys.first().toECKey()

        // Step 3: Extract public key from x5c header and cross-check with JWKS
        val x5cKey = extractKeyFromX5c(signedJwt)
        if (!keysMatch(jwksKey, x5cKey)) {
            throw SecurityException("x5c key does not match issuer JWKS - possible tampering")
        }
        Log.d("WalletApp", "x5c key matches JWKS key - cross-check passed")

        // Step 4: Verify the SD-JWT signature using JWKS key
        val verifier = ECDSAVerifier(jwksKey)
        val isValid = signedJwt.verify(verifier)
        Log.d("WalletApp", "Signature valid: $isValid")

        if (!isValid) throw RuntimeException("Invalid SD-JWT signature!")
    }

    /**
     * Extracts the issuer's public key from the x5c certificate chain in the JWT header.
     */
    private fun extractKeyFromX5c(signedJwt: SignedJWT): ECKey {
        val x5cChain = signedJwt.header.x509CertChain
            ?: throw IllegalArgumentException("No x5c certificate chain in credential JWT header")

        if (x5cChain.isEmpty()) {
            throw IllegalArgumentException("Empty x5c certificate chain")
        }

        // Parse leaf certificate (first in chain)
        val certBytes = x5cChain[0].decode()
        val certFactory = CertificateFactory.getInstance("X.509")
        val certificate = certFactory.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate

        // Extract EC public key and build JWK
        val ecPublicKey = certificate.publicKey as ECPublicKey
        return ECKey.Builder(Curve.P_256, ecPublicKey).build()
    }

    /**
     * Compares two ECKeys by their thumbprint.
     */
    private fun keysMatch(jwksKey: ECKey, x5cKey: ECKey): Boolean {
        return try {
            val jwksThumbprint = jwksKey.computeThumbprint().toString()
            val x5cThumbprint = x5cKey.computeThumbprint().toString()
            jwksThumbprint == x5cThumbprint
        } catch (e: Exception) {
            Log.e("WalletApp", "Failed to compute key thumbprints", e)
            false
        }
    }

    fun decodeCredential(sdJwt: String): Map<String, String> {
        Log.d("WalletApp", "Decoding SD-JWT...")

        val parts = sdJwt.split("~")
        val disclosures = parts.drop(1).filter { it.isNotEmpty() }

        Log.d("WalletApp", "Number of disclosures: ${disclosures.size}")

        val decodedClaims = mutableMapOf<String, String>()

        for (disclosure in disclosures) {
            try {
                val decodedBytes = Base64.decode(disclosure, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                val decodedJson = String(decodedBytes)
                val claimData = json.parseToJsonElement(decodedJson).jsonArray

                if (claimData.size >= 3) {
                    val claimName = claimData[1].jsonPrimitive.content
                    val claimValue = claimData[2].jsonPrimitive.content
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
        }
    }

}