package com.example.eudiwemu.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class AuthorizationRequestResponse(
    val client_id: String? = null,
    val response_uri: String,
    val response_type: String,
    val response_mode: String,
    val nonce: String,
    val state: String? = null,  // Optional state to echo back in response
    val dcql_query: DcqlQuery,
    val client_metadata: ClientMetadata? = null
)

@Serializable
data class ClientMetadata(
    val client_name: String? = null,
    val logo_uri: String? = null,
    val purpose: String? = null,
    // Encryption parameters for direct_post.jwt response mode
    val jwks: JwksObject? = null,
    val authorization_encrypted_response_alg: String? = null,
    val authorization_encrypted_response_enc: String? = null
)

@Serializable
data class JwksObject(
    val keys: List<JsonObject>
)

// DCQL (Digital Credentials Query Language) per OpenID4VP 1.0
@Serializable
data class DcqlQuery(
    val credentials: List<CredentialQuery>
)

@Serializable
data class CredentialQuery(
    val id: String,
    val format: String,
    val meta: CredentialMeta? = null,
    val claims: List<ClaimQuery>? = null
)

@Serializable
data class CredentialMeta(
    val vct_values: List<String>? = null
)

@Serializable
data class ClaimQuery(
    val path: List<String>,
    val id: String? = null
)
