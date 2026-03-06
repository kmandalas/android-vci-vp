package com.example.eudiwemu.model

import kotlinx.serialization.Serializable

/**
 * Holds dynamically discovered endpoints and state for an OID4VCI issuance session.
 * Created when the wallet receives a credential offer deep link.
 * Serializable for persistence across OAuth redirects.
 */
@Serializable
data class IssuerSession(
    val credentialIssuerUrl: String,
    val credentialEndpoint: String,
    val nonceEndpoint: String,
    val tokenEndpoint: String,
    val authorizationEndpoint: String,
    val parEndpoint: String? = null,
    val credentialConfigurationIds: List<String>,
    val scope: String? = null,
    val issuerState: String? = null,
    val authServerIssuer: String,
    val sendWia: Boolean = true,
    val credentialDisplayName: String? = null,
    val useAuthorizationDetails: Boolean = false
)
