package com.example.eudiwemu.dto

import kotlinx.serialization.Serializable

@Serializable
data class CredentialIssuerMetadata(
    val credential_issuer: String,
    val credential_configurations_supported: Map<String, CredentialConfiguration>
)

@Serializable
data class CredentialConfiguration(
    val format: String,
    val vct: String? = null,
    val display: List<CredentialDisplay>? = null,
    val claims: List<ClaimMetadata>? = null
)

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
