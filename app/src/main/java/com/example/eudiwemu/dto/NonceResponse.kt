package com.example.eudiwemu.dto

import kotlinx.serialization.Serializable

@Serializable
data class NonceResponse(
    val c_nonce: String,
    val c_nonce_expires_in: Int
)
