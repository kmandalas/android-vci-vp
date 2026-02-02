package com.example.eudiwemu.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ==================== Request DTOs ====================

/**
 * Request body for POST /wp/wia/credential
 */
@Serializable
data class WiaCredentialRequest(
    @SerialName("client_id") val clientId: String,
    val proof: WiaProof
)

/**
 * JWT proof object for WIA credential request
 */
@Serializable
data class WiaProof(
    @SerialName("proof_type") val proofType: String = "jwt",
    val jwt: String
)

// ==================== Response DTOs ====================

/**
 * Response from GET /wp/wia/nonce
 */
@Serializable
data class WiaNonceResponse(
    @SerialName("c_nonce") val cNonce: String,
    @SerialName("c_nonce_expires_in") val cNonceExpiresIn: Int
)

/**
 * Response from POST /wp/wia/credential (success)
 */
@Serializable
data class WiaCredentialResponse(
    val format: String,
    val credential: String,
    @SerialName("wia_id") val wiaId: String
)

/**
 * Response from POST /wp/wia/credential (error)
 */
@Serializable
data class WiaErrorResponse(
    val error: String,
    @SerialName("error_description") val errorDescription: String? = null
)
