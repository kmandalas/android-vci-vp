package com.example.eudiwemu.dto

import kotlinx.serialization.Serializable

@Serializable
data class AuthorizationRequestResponse(
    val client_id: String? = null,  // Nullable client_id
    val response_uri: String,
    val response_type: String,
    val response_mode: String,
    val nonce: String,
    val presentation_definition: PresentationDefinition
)

@Serializable
data class PresentationDefinition(
    val id: String,
    val input_descriptors: List<InputDescriptor>
)

@Serializable
data class InputDescriptor(
    val id: String,
    val format: Map<String, Map<String, List<String>>>,
    val constraints: Constraints
)

@Serializable
data class Constraints(
    val fields: List<Field>
)

@Serializable
data class Field(
    val path: List<String>,
    val purpose: String
)