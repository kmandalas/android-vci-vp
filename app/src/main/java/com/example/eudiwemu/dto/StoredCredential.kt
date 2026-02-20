package com.example.eudiwemu.dto

import kotlinx.serialization.Serializable

/**
 * Bundled storage representation for a single credential.
 * All metadata is stored together as one JSON blob in encrypted SharedPreferences.
 */
@Serializable
data class StoredCredential(
    val rawCredential: String,
    val format: String,
    val claimsMetadata: List<ClaimMetadata>? = null,
    val displayMetadata: List<CredentialDisplay>? = null,
    val issuedAt: Long? = null,
    val expiresAt: Long? = null
)
