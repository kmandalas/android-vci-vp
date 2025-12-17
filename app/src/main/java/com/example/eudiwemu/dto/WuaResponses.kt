package com.example.eudiwemu.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ==================== Request DTOs ====================

/**
 * Request body for POST /wp/wua/credential
 */
@Serializable
data class WuaCredentialRequest(
    val proof: WuaProof,
    @SerialName("key_attestation") val keyAttestation: WuaKeyAttestation
)

/**
 * JWT proof object for WUA credential request
 */
@Serializable
data class WuaProof(
    @SerialName("proof_type") val proofType: String = "jwt",
    val jwt: String
)

/**
 * Android Key Attestation data for WUA credential request
 */
@Serializable
data class WuaKeyAttestation(
    @SerialName("attestation_type") val attestationType: String = "android_key_attestation",
    @SerialName("certificate_chain") val certificateChain: List<String>
)

// ==================== Response DTOs ====================

/**
 * Response from GET /wp/wua/nonce
 */
@Serializable
data class WuaNonceResponse(
    @SerialName("c_nonce") val cNonce: String,
    @SerialName("c_nonce_expires_in") val cNonceExpiresIn: Int
)

/**
 * Response from POST /wp/wua/credential (success)
 */
@Serializable
data class WuaCredentialResponse(
    val format: String,
    val credential: String,
    @SerialName("wua_id") val wuaId: String
)

/**
 * Response from POST /wp/wua/credential (error)
 */
@Serializable
data class WuaErrorResponse(
    val error: String,
    @SerialName("error_description") val errorDescription: String? = null
)

/**
 * Response from GET /wp/wua/status/{wuaId}
 */
@Serializable
data class WuaStatusResponse(
    @SerialName("wua_id") val wuaId: String,
    val status: String,
    @SerialName("wscd_type") val wscdType: String,
    @SerialName("wscd_security_level") val wscdSecurityLevel: String,
    @SerialName("issued_at") val issuedAt: String,
    @SerialName("expires_at") val expiresAt: String
)
