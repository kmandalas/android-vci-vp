package dev.kmandalas.wallet.dto

import kotlinx.serialization.Serializable

@Serializable
data class CredentialOffer(
    val credential_issuer: String,
    val credential_configuration_ids: List<String>,
    val grants: CredentialOfferGrants? = null
)

@Serializable
data class CredentialOfferGrants(
    val authorization_code: AuthorizationCodeGrant? = null
)

@Serializable
data class AuthorizationCodeGrant(
    val issuer_state: String? = null,
    val authorization_server: String? = null
)
