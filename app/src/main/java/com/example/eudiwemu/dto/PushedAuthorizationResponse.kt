package com.example.eudiwemu.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PushedAuthorizationResponse(
    @SerialName("request_uri")
    val requestUri: String,
    @SerialName("expires_in")
    val expiresIn: Int
)
