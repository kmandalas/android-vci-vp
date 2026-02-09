package com.example.eudiwemu.dto

import kotlinx.serialization.Serializable

@Serializable
data class CredentialIssuerMetadata(
    val credential_issuer: String,
    val credential_configurations_supported: Map<String, CredentialConfiguration>
)

@Serializable
data class CredentialMetadata(
    val claims: List<ClaimMetadata>? = null,
    val display: List<CredentialDisplay>? = null
)

@Serializable
data class CredentialConfiguration(
    val format: String,
    val vct: String? = null,
    val credential_metadata: CredentialMetadata? = null,
    // Keep old fields for backward compat with other issuers
    val display: List<CredentialDisplay>? = null,
    val claims: List<ClaimMetadata>? = null
) {
    /** Resolve claims preferring credential_metadata wrapper, falling back to top-level. */
    fun resolvedClaims(): List<ClaimMetadata>? = credential_metadata?.claims ?: claims

    /** Resolve display preferring credential_metadata wrapper, falling back to top-level. */
    fun resolvedDisplay(): List<CredentialDisplay>? = credential_metadata?.display ?: display
}

@Serializable
data class CredentialDisplay(
    val name: String,
    val locale: String? = null
)

@Serializable
data class ClaimMetadata(
    val path: List<String>,
    val display: List<ClaimDisplay>? = null,
    val mandatory: Boolean = false
)

@Serializable
data class ClaimDisplay(
    val name: String,
    val locale: String? = null
)
