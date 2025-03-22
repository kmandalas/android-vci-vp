package com.example.eudiwemu.dto

import kotlinx.serialization.Serializable

@Serializable
data class AccessTokenResponse(
    val access_token: String,
    val token_type: String,
    val scope: String,
    val expires_in: Int
)
