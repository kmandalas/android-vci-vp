package dev.kmandalas.wallet.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ==================== CSC API v2 Response DTOs ====================

@Serializable
data class QtspInfoResponse(
    val specs: String,
    val name: String,
    val logo: String = "",
    val region: String = "",
    val lang: String = "",
    val description: String = "",
    val authType: List<String> = emptyList(),
    val methods: List<String> = emptyList()
)

@Serializable
data class QtspCredentialsListResponse(
    @SerialName("credentialIDs") val credentialIds: List<String>
)

@Serializable
data class QtspCredentialInfoResponse(
    val description: String? = null,
    val key: QtspKeyInfo? = null,
    val cert: QtspCertInfo? = null,
    val auth: QtspAuthInfo? = null,
    @SerialName("SCAL") val scal: String? = null
) {
    /**
     * Build the qtsp_credential_info object to send to the wallet-provider.
     * Mirrors the backend's WuaCredentialRequest.QtspCredentialInfo shape.
     */
    fun toQtspCredentialInfo(): QtspCredentialInfoForWua = QtspCredentialInfoForWua(
        key = key,
        cert = cert,
        scal = scal
    )
}

@Serializable
data class QtspKeyInfo(
    val status: String? = null,
    val algo: List<String> = emptyList(),
    val len: Int = 0,
    val curve: String? = null
)

@Serializable
data class QtspCertInfo(
    val status: String? = null,
    val certificates: List<String> = emptyList(),
    val issuerDN: String? = null,
    val subjectDN: String? = null
)

@Serializable
data class QtspAuthInfo(
    val mode: String? = null,
    val expression: List<String> = emptyList()
)

@Serializable
data class QtspAuthorizeResponse(
    @SerialName("SAD") val sad: String,
    val expiresIn: Long = 0
)

@Serializable
data class QtspSignHashResponse(
    val signatures: List<String>
)

// ==================== Request DTOs ====================

@Serializable
data class QtspCredentialsListRequest(
    @SerialName("userID") val userId: String? = null,
    val maxResults: Int? = null
)

@Serializable
data class QtspCredentialInfoRequest(
    @SerialName("credentialID") val credentialId: String,
    val certificates: String? = "chain",
    val certInfo: Boolean? = true,
    val authInfo: Boolean? = true
)

@Serializable
data class QtspCredentialAuthorizeRequest(
    @SerialName("credentialID") val credentialId: String,
    val numSignatures: Int? = 1,
    val hash: List<String>? = null,
    @SerialName("PIN") val pin: String? = null
)

@Serializable
data class QtspSignHashRequest(
    @SerialName("credentialID") val credentialId: String,
    @SerialName("SAD") val sad: String,
    val hash: List<String>,
    val hashAlgo: String = "2.16.840.1.101.3.4.2.1", // SHA-256 OID
    val signAlgo: String = "1.2.840.10045.4.3.2" // SHA256withECDSA OID
)

// ==================== WUA integration DTO ====================

/**
 * Matches the backend's WuaCredentialRequest.QtspCredentialInfo shape.
 * Sent to wallet-provider as part of qtsp_attestation key_attestation.
 */
@Serializable
data class QtspCredentialInfoForWua(
    val key: QtspKeyInfo? = null,
    val cert: QtspCertInfo? = null,
    @SerialName("SCAL") val scal: String? = null
)
