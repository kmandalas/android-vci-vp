package com.example.eudiwemu.service

import android.util.Log
import com.authlete.sd.Disclosure
import com.authlete.sd.SDJWT
import com.example.eudiwemu.config.AppConfig
import com.example.eudiwemu.dto.AuthorizationRequestResponse
import com.example.eudiwemu.dto.Field
import com.example.eudiwemu.security.AndroidKeystoreSigner
import com.example.eudiwemu.security.WalletKeyManager
import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.text.ParseException
import java.util.UUID

class VpTokenService(
    private val client: HttpClient,
    private val walletKeyManager: WalletKeyManager
) {

    suspend fun getRequestObject(requestUri: String): AuthorizationRequestResponse {
        val response: String = client.get(requestUri).body()
        return Json.decodeFromString(response)
    }

    suspend fun sendVpTokenToVerifier(vpToken: String, responseUri: String): String {
        try {
            val response: HttpResponse = client.post(responseUri) {
                contentType(ContentType.Application.Json)
                setBody(mapOf("vp_token" to vpToken))
            }

            val responseBody: String = response.body()

            Log.d("VP Verification Response", responseBody)
            return responseBody
        } catch (e: Exception) {
            Log.e("VpTokenService", "Error sending VP Token to Verifier", e)
            throw e
        }
    }

    fun extractRequestedClaims(
        request: AuthorizationRequestResponse,
        storedCredential: String
    ): List<Disclosure> {
        val jwtPart = storedCredential.substringBefore("~")
        val signedJwt = SignedJWT.parse(jwtPart)
        val existingVct = signedJwt.jwtClaimsSet.getStringClaim("vct")

        val allFields = request.presentation_definition.input_descriptors
            .flatMap { it.constraints.fields }

        val requestedVct = allFields
            .firstOrNull { it.path.contains("$.vct") }
            ?.filter?.const

        checkVctMatch(existingVct, requestedVct)

        val requestedClaimPaths = extractClaimPaths(allFields)
        val allDisclosures = parseSDJWT(storedCredential)

        return allDisclosures.filter { disclosure ->
            requestedClaimPaths.any { path -> path.contains(disclosure.claimName) }
        }
    }

    private fun extractClaimPaths(fields: List<Field>): List<String> =
        fields.mapNotNull { it.path.firstOrNull()?.removePrefix("$.") }

    private fun checkVctMatch(actual: String?, expected: String?) {
        if (actual != expected) {
            throw IllegalArgumentException("❌ VCT mismatch: expected '$expected', but found '$actual'.")
        }
    }

    private fun parseSDJWT(storedCredential: String): List<Disclosure> {
        return try {
            SDJWT.parse(storedCredential).disclosures
        } catch (e: Exception) {
            throw RuntimeException("Error parsing SD-JWT: ${e.message}", e)
        }
    }


    // Function to create Verifiable Presentation (VP)
    fun createVP(credential: String, disclosures: List<Disclosure> = listOf()): SDJWT {
        // Parse the provided credential into an SDJWT
        val vc = SDJWT.parse(credential)

        // If no disclosures are provided, include only the first one
        val disclosuresToUse = disclosures.ifEmpty { listOf(vc.disclosures[0]) }

        // Intended audience of the verifiable presentation
        val audience = listOf("https://verifier.example.com") // todo

        // Create the binding JWT, part of the verifiable presentation
        val bindingJwt = createBindingJwt(vc, disclosuresToUse, audience, walletKeyManager.getWalletKey())

        // Create and return a Verifiable Presentation (VP) in SD-JWT format
        return SDJWT(vc.credentialJwt, disclosuresToUse, bindingJwt.serialize())
    }

    // Function to create the binding JWT
    @Throws(ParseException::class, JOSEException::class)
    private fun createBindingJwt(vc: SDJWT, disclosures: List<Disclosure>, audience: List<String>, signingKey: JWK): SignedJWT {
        // Create the header part of a binding JWT
        val header = createBindingJwtHeader(signingKey)

        // Create the payload part of a binding JWT
        val payload = createBindingJwtPayload(vc, disclosures, audience)

        // Create a binding JWT (not signed yet)
        val jwt = SignedJWT(header, JWTClaimsSet.parse(payload))

        // Create a signer
        // val signer: JWSSigner = DefaultJWSSignerFactory().createJWSSigner(signingKey)
        val signer = AndroidKeystoreSigner(AppConfig.KEY_ALIAS) // ✅ Uses Android Keystore API

        // Let the signer sign the binding JWT
        jwt.sign(signer)

        // Return the signed binding JWT
        return jwt
    }

    // Function to create the header for the binding JWT
    private fun createBindingJwtHeader(signingKey: JWK): JWSHeader {
        // The signing algorithm
        val alg = JWSAlgorithm.parse(signingKey.algorithm.name)

        // The key ID
        val kid = signingKey.keyID

        // Create the header for the binding JWT
        return JWSHeader.Builder(alg)
            .keyID(kid)
            .type(JOSEObjectType("kb+jwt")) // see verifyBindingJwt on server-side
            .build()
    }

    // Function to create the payload for the binding JWT
    private fun createBindingJwtPayload(vc: SDJWT, disclosures: List<Disclosure>, audience: List<String>): Map<String, Any> {
        val payload = LinkedHashMap<String, Any>()

        // "iat" - The issuance time of the binding JWT
        payload["iat"] = System.currentTimeMillis() / 1000L

        // "aud" - The intended receiver of the binding JWT
        payload["aud"] = audience

        // "nonce" - A random value ensuring the freshness of the signature
        payload["nonce"] = UUID.randomUUID().toString()

        // "sd_hash" - The base64url-encoded hash value over the Issuer-signed JWT and the selected disclosures
        payload["sd_hash"] = computeSdHash(vc, disclosures)

        return payload
    }

    // Function to compute the SD hash value
    private fun computeSdHash(vc: SDJWT, disclosures: List<Disclosure>): String {
        // Compute the SD hash value using the credential JWT and disclosures
        return SDJWT(vc.credentialJwt, disclosures).sdHash
    }

}