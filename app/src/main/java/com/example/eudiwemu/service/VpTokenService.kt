package com.example.eudiwemu.service

import android.util.Log
import com.authlete.sd.Disclosure
import com.example.eudiwemu.config.AppConfig
import com.example.eudiwemu.dto.AuthorizationRequestResponse
import com.example.eudiwemu.dto.ClaimMetadata
import com.example.eudiwemu.service.mdoc.DeviceResponseBuilder
import com.example.eudiwemu.service.mdoc.SessionTranscriptBuilder
import com.example.eudiwemu.service.sdjwt.SdJwtVpService
import com.example.eudiwemu.security.WalletKeyManager
import com.nimbusds.jose.EncryptionMethod
import com.nimbusds.jose.JWEAlgorithm
import com.nimbusds.jose.JWEHeader
import com.nimbusds.jose.JWEObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.ECDHEncrypter
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.ECKey
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.util.Base64

class VpTokenService(
    private val client: HttpClient,
    walletKeyManager: WalletKeyManager
) {
    companion object {
        private const val TAG = "VpTokenService"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val sdJwtVpService = SdJwtVpService(walletKeyManager)

    /**
     * Fetches and verifies the signed Authorization Request (JAR) from the request URI.
     * Validates the JWT signature using the x5c certificate and verifies x509_hash client_id.
     */
    suspend fun getRequestObject(requestUri: String, expectedClientId: String): AuthorizationRequestResponse {
        val jwtString: String = client.get(requestUri) {
            accept(ContentType("application", "oauth-authz-req+jwt"))
        }.body()

        val signedJwt = com.nimbusds.jwt.SignedJWT.parse(jwtString)
        val header = signedJwt.header

        val x5cChain = header.x509CertChain
            ?: throw IllegalArgumentException("Missing x5c header in authorization request JWT")

        if (x5cChain.isEmpty()) {
            throw IllegalArgumentException("Empty x5c certificate chain")
        }

        val certBytes = x5cChain[0].decode()
        val certFactory = CertificateFactory.getInstance("X.509")
        val certificate = certFactory.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate

        val publicKey = certificate.publicKey as ECPublicKey
        val verifier = ECDSAVerifier(publicKey)

        if (!signedJwt.verify(verifier)) {
            throw SecurityException("Invalid JWT signature - authorization request not trusted")
        }

        Log.d(TAG, "JAR signature verified successfully")

        val computedHash = computeX509Hash(certificate)
        val expectedHash = if (expectedClientId.startsWith("x509_hash:")) {
            expectedClientId.substringAfter("x509_hash:")
        } else {
            expectedClientId
        }

        if (computedHash != expectedHash) {
            throw SecurityException("x509_hash mismatch: computed=$computedHash, expected=$expectedHash")
        }

        Log.d(TAG, "x509_hash validated: $computedHash")

        val payloadJson = signedJwt.payload.toString()
        return json.decodeFromString<AuthorizationRequestResponse>(payloadJson)
    }

    private fun computeX509Hash(certificate: X509Certificate): String {
        val derEncoded = certificate.encoded
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(derEncoded)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
    }

    suspend fun sendVpTokenToVerifier(
        vpToken: String,
        responseUri: String,
        request: AuthorizationRequestResponse
    ): String {
        try {
            Log.d(TAG, "response_mode='${request.response_mode}', " +
                    "has_jwks=${request.client_metadata?.jwks != null}")

            val response: HttpResponse = if (request.response_mode == "direct_post.jwt") {
                val encryptedResponse = encryptVpResponse(vpToken, request)
                client.post(responseUri) {
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("response=${java.net.URLEncoder.encode(encryptedResponse, "UTF-8")}")
                }
            } else {
                client.post(responseUri) {
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("vp_token=${java.net.URLEncoder.encode(vpToken, "UTF-8")}")
                }
            }

            val responseBody: String = response.body()
            Log.d(TAG, "VP Verification Response: $responseBody")
            return responseBody
        } catch (e: Exception) {
            Log.e(TAG, "Error sending VP Token to Verifier", e)
            throw e
        }
    }

    private fun encryptVpResponse(vpToken: String, request: AuthorizationRequestResponse): String {
        val clientMetadata = request.client_metadata
            ?: throw IllegalArgumentException("client_metadata required for direct_post.jwt")

        val jwks = clientMetadata.jwks
            ?: throw IllegalArgumentException("jwks required in client_metadata for encryption")

        val keyJson = jwks.keys.firstOrNull()
            ?: throw IllegalArgumentException("No keys in JWKS")

        val verifierPublicKey = ECKey.parse(keyJson.toString())

        val encMethod = when (clientMetadata.authorization_encrypted_response_enc) {
            "A128GCM" -> EncryptionMethod.A128GCM
            else -> EncryptionMethod.A256GCM
        }

        val headerBuilder = JWEHeader.Builder(JWEAlgorithm.ECDH_ES, encMethod)
        verifierPublicKey.keyID?.let { headerBuilder.keyID(it) }
        val header = headerBuilder.build()

        val credentialId = request.dcql_query.credentials.firstOrNull()?.id ?: "credential"
        Log.d(TAG, "Using credential ID: $credentialId, state: ${request.state}")

        val stateJson = request.state?.let { ""","state":"$it"""" } ?: ""
        val payloadJson = """{"vp_token":{"$credentialId":["$vpToken"]}$stateJson}"""

        Log.d(TAG, "Encrypted payload structure: vp_token={$credentialId:...}, " +
                "state=${request.state != null}")
        val payload = Payload(payloadJson)

        val jweObject = JWEObject(header, payload)
        val encrypter = ECDHEncrypter(verifierPublicKey)
        jweObject.encrypt(encrypter)

        Log.d(TAG, "VP response encrypted with ECDH-ES + $encMethod")
        return jweObject.serialize()
    }

    // --- SD-JWT delegation ---

    fun extractRequestedClaims(
        request: AuthorizationRequestResponse,
        storedCredential: String,
        claimsMetadata: List<ClaimMetadata>? = null
    ): SdJwtVpService.RequestedClaimsResult {
        return sdJwtVpService.extractRequestedClaims(request, storedCredential, claimsMetadata)
    }

    fun createSdJwtVpToken(
        credential: String,
        disclosures: List<Disclosure> = listOf(),
        clientId: String,
        nonce: String
    ): String {
        return sdJwtVpService.createVpToken(credential, disclosures, clientId, nonce)
    }

    // --- mDoc ---

    /**
     * Create an mDoc VP token (DeviceResponse) with DeviceAuth.
     */
    fun createMDocVpToken(
        credential: String,
        selectedClaims: List<String>,
        clientId: String,
        nonce: String,
        responseUri: String,
        ephemeralJwk: JsonObject?
    ): String {
        Log.d(TAG, "Creating mDoc VP token with ${selectedClaims.size} selected claims")

        val sessionTranscript = SessionTranscriptBuilder.build(
            clientId = clientId,
            nonce = nonce,
            ephemeralJwk = ephemeralJwk,
            responseUri = responseUri
        )

        val vpToken = DeviceResponseBuilder.build(
            credential = credential,
            selectedClaims = selectedClaims,
            sessionTranscript = sessionTranscript
        )

        Log.d(TAG, "mDoc VP token created successfully")
        return vpToken
    }

    /**
     * Extract the credential format from a DCQL query.
     */
    fun extractFormatFromDcql(request: AuthorizationRequestResponse): String {
        return request.dcql_query.credentials.firstOrNull()?.format ?: AppConfig.FORMAT_SD_JWT
    }
}
