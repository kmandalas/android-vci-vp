package com.example.eudiwemu.service

import com.example.eudiwemu.dto.AccessTokenResponse
import com.example.eudiwemu.dto.NonceResponse
import com.example.eudiwemu.security.AndroidKeystoreSigner
import com.example.eudiwemu.security.WalletKeyManager
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.basicAuth
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.util.Date

class IssuanceService(
    private val client: HttpClient,
    private val walletKeyManager: WalletKeyManager
) {

    companion object {
        private const val AUTH_SERVER_URL = "http://192.168.1.65:9000/oauth2/token"
        private const val ISSUER_URL = "http://192.168.1.65:8080"
        private const val CLIENT_ID = "wallet-client"
        private const val CLIENT_SECRET = "wallet-secret"
        private const val KEY_ALIAS = "wallet-key"
    } // todo ⚠️

    suspend fun obtainAccessToken(): AccessTokenResponse {
        val response: AccessTokenResponse = client.post(AUTH_SERVER_URL) {
            contentType(ContentType.Application.FormUrlEncoded)
            basicAuth(CLIENT_ID, CLIENT_SECRET)
            setBody("grant_type=client_credentials&scope=openid vc:issue")
        }.body()

        return response
    }

    suspend fun getNonce(accessToken: String): String {
        val response: NonceResponse = client.get("$ISSUER_URL/credential/nonce") {
            header("Authorization", "Bearer $accessToken") // Explicitly setting it
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
            .audience(ISSUER_URL)
            .issueTime(Date())
            .expirationTime(Date(System.currentTimeMillis() + 300000))
            .claim("nonce", nonce)
            .build()

        val signedJWT = SignedJWT(header, claims)
        // val signer = ECDSASigner(ecKey.toECPrivateKey())
        val signer = AndroidKeystoreSigner(KEY_ALIAS) // ✅ Uses Android Keystore API
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

        val response: HttpResponse = client.post("$ISSUER_URL/credential") {
            contentType(ContentType.Application.Json)
            bearerAuth(accessToken)
            setBody(requestBody)
        }
        val jsonResponse: Map<String, String> = response.body()
        return jsonResponse["credential"] ?: throw Exception("Failed to request credential")
    }

}