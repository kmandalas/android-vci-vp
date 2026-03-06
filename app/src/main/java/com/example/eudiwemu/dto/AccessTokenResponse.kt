package com.example.eudiwemu.dto

import kotlinx.serialization.Serializable

@Serializable
data class AccessTokenResponse(
    val access_token: String,
    val token_type: String,
    val scope: String? = null,
    val expires_in: Int = 0,
    val c_nonce: String? = null,
    val c_nonce_expires_in: Int? = null,
    val authorization_details: kotlinx.serialization.json.JsonElement? = null
)
