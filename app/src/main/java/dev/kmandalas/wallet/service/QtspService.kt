package dev.kmandalas.wallet.service

import android.util.Log
import dev.kmandalas.wallet.config.AppConfig
import dev.kmandalas.wallet.dto.QtspAuthorizeResponse
import dev.kmandalas.wallet.dto.QtspCredentialAuthorizeRequest
import dev.kmandalas.wallet.dto.QtspCredentialInfoRequest
import dev.kmandalas.wallet.dto.QtspCredentialInfoResponse
import dev.kmandalas.wallet.dto.QtspCredentialsListRequest
import dev.kmandalas.wallet.dto.QtspCredentialsListResponse
import dev.kmandalas.wallet.dto.QtspInfoResponse
import dev.kmandalas.wallet.dto.QtspSignHashRequest
import dev.kmandalas.wallet.dto.QtspSignHashResponse
import com.nimbusds.jose.crypto.impl.ECDSA
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.security.MessageDigest
import java.util.Base64

/**
 * CSC API v2 client for communicating with the QTSP (mock or real).
 *
 * All endpoints are under /csc/v2/ on the configured QTSP URL.
 * Authentication uses a static API key in the Authorization header.
 */
class QtspService(private val client: HttpClient) {

    companion object {
        private const val TAG = "QtspService"
    }

    private val baseUrl: String get() = "${AppConfig.QTSP_URL}/csc/v2"

    /**
     * GET /csc/v2/info — QTSP server info.
     */
    suspend fun getInfo(): QtspInfoResponse {
        Log.d(TAG, "🔌 Fetching QTSP info from $baseUrl/info")
        val response: QtspInfoResponse = client.get("$baseUrl/info") {
            header("Authorization", "Bearer ${AppConfig.QTSP_API_KEY}")
        }.body()
        Log.d(TAG, "🔌 QTSP: ${response.name} (${response.specs})")
        return response
    }

    /**
     * POST /csc/v2/credentials/list — list available credential IDs.
     */
    suspend fun listCredentials(): List<String> {
        Log.d(TAG, "📋 Listing QTSP credentials")
        val response: QtspCredentialsListResponse = client.post("$baseUrl/credentials/list") {
            header("Authorization", "Bearer ${AppConfig.QTSP_API_KEY}")
            contentType(ContentType.Application.Json)
            setBody(QtspCredentialsListRequest())
        }.body()
        Log.d(TAG, "📋 QTSP credentials: ${response.credentialIds}")
        return response.credentialIds
    }

    /**
     * POST /csc/v2/credentials/info — get credential details (key, cert, SCAL).
     */
    suspend fun getCredentialInfo(credentialId: String): QtspCredentialInfoResponse {
        Log.d(TAG, "🔍 Fetching QTSP credential info for: $credentialId")
        val response: QtspCredentialInfoResponse = client.post("$baseUrl/credentials/info") {
            header("Authorization", "Bearer ${AppConfig.QTSP_API_KEY}")
            contentType(ContentType.Application.Json)
            setBody(QtspCredentialInfoRequest(credentialId = credentialId))
        }.body()
        Log.d(TAG, "🔍 QTSP credential: SCAL=${response.scal}, key=${response.key?.status}")
        return response
    }

    /**
     * POST /csc/v2/credentials/authorize — get a single-use SAD token.
     */
    suspend fun authorize(credentialId: String): QtspAuthorizeResponse {
        Log.d(TAG, "🔐 Authorizing QTSP credential: $credentialId")
        val response: QtspAuthorizeResponse = client.post("$baseUrl/credentials/authorize") {
            header("Authorization", "Bearer ${AppConfig.QTSP_API_KEY}")
            contentType(ContentType.Application.Json)
            setBody(QtspCredentialAuthorizeRequest(credentialId = credentialId))
        }.body()
        Log.d(TAG, "🔐 QTSP SAD obtained (expires in ${response.expiresIn}s)")
        return response
    }

    /**
     * POST /csc/v2/signatures/signHash — sign one or more hashes with the QTSP-managed key.
     *
     * @param credentialId QTSP credential ID
     * @param sad          Single-use authorization token (from authorize())
     * @param hashes       Base64-encoded SHA-256 hashes to sign
     * @return List of Base64-encoded signatures (DER format)
     */
    suspend fun signHash(credentialId: String, sad: String, hashes: List<String>): List<String> {
        Log.d(TAG, "✍️ Signing ${hashes.size} hash(es) with QTSP credential: $credentialId")
        val response: QtspSignHashResponse = client.post("$baseUrl/signatures/signHash") {
            header("Authorization", "Bearer ${AppConfig.QTSP_API_KEY}")
            contentType(ContentType.Application.Json)
            setBody(QtspSignHashRequest(
                credentialId = credentialId,
                sad = sad,
                hash = hashes
            ))
        }.body()
        Log.d(TAG, "✍️ QTSP returned ${response.signatures.size} signature(s)")
        return response.signatures
    }

    /**
     * Hash → authorize → signHash → DER-to-JOSE signing pipeline.
     *
     * Centralizes the common pattern used by both JWSSigner (RemoteQtspSigner)
     * and COSE signing (DeviceResponseBuilder).
     *
     * @param credentialId QTSP credential ID
     * @param data         Raw bytes to sign (will be SHA-256 hashed before sending to QTSP)
     * @return Raw R||S JOSE signature bytes (64 bytes for P-256)
     */
    suspend fun signRaw(credentialId: String, data: ByteArray): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256").digest(data)
        val hashBase64 = Base64.getEncoder().encodeToString(hash)
        Log.d(TAG, "🔐 Remote signing: hash=${hashBase64.take(20)}...")

        val authResponse = authorize(credentialId)
        val signatures = signHash(credentialId, authResponse.sad, listOf(hashBase64))

        val derSignature = Base64.getDecoder().decode(signatures.first())
        val joseSignature = ECDSA.transcodeSignatureToConcat(derSignature, 64)
        Log.d(TAG, "🔐 Remote signature obtained (${joseSignature.size} bytes)")
        return joseSignature
    }

}
