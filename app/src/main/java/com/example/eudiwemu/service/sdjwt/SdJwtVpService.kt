package com.example.eudiwemu.service.sdjwt

import android.util.Log
import com.authlete.sd.Disclosure
import com.authlete.sd.SDJWT
import com.example.eudiwemu.config.AppConfig
import com.example.eudiwemu.dto.AuthorizationRequestResponse
import com.example.eudiwemu.dto.ClaimMetadata
import com.example.eudiwemu.util.ClaimMetadataResolver
import com.example.eudiwemu.security.AndroidKeystoreSigner
import com.example.eudiwemu.security.WalletKeyManager
import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.text.ParseException

class SdJwtVpService(private val walletKeyManager: WalletKeyManager) {

    companion object {
        private const val TAG = "SdJwtVpService"
    }

    data class RequestedClaimsResult(
        val disclosures: List<Disclosure>,
        val resolver: ClaimMetadataResolver?
    )

    /**
     * Extracts disclosures for requested claims with two-level recursive disclosure support.
     *
     * When a parent claim is requested (e.g., "credential_holder"), this method includes:
     * - The parent disclosure itself (contains nested _sd array)
     * - ALL nested child disclosures (family_name, given_name, birth_date)
     *
     * Uses ClaimMetadataResolver when available for dynamic parent-to-children mapping,
     * falling back to hardcoded maps if no metadata is stored.
     */
    fun extractRequestedClaims(
        request: AuthorizationRequestResponse,
        storedCredential: String,
        claimsMetadata: List<ClaimMetadata>? = null
    ): RequestedClaimsResult {
        val jwtPart = storedCredential.substringBefore("~")
        val signedJwt = SignedJWT.parse(jwtPart)
        val existingVct = signedJwt.jwtClaimsSet.getStringClaim("vct")

        // DCQL: Get the first credential query (assuming single credential request)
        val credentialQuery = request.dcql_query.credentials.firstOrNull()
            ?: throw IllegalArgumentException("No credential query in DCQL request")

        // Validate VCT matches
        val requestedVcts = credentialQuery.meta?.vct_values
        if (requestedVcts != null && existingVct !in requestedVcts) {
            throw IllegalArgumentException("VCT mismatch: expected $requestedVcts, found '$existingVct'")
        }

        // Extract requested parent claim names from DCQL paths
        val requestedParents = credentialQuery.claims
            ?.mapNotNull { it.path.firstOrNull() }
            ?.toSet() ?: emptySet()

        Log.d(TAG, "Requested parent claims: $requestedParents")

        val allDisclosures = parseSDJWT(storedCredential)
        Log.d(TAG, "Available disclosures: ${allDisclosures.map { it.claimName }}")

        val resolver = ClaimMetadataResolver.fromNullable(claimsMetadata)

        // Build parent -> children mapping from metadata, or fall back to hardcoded
        val parentToChildren: Map<String, Set<String>> = if (resolver != null) {
            val groups = resolver.groupByParent()
            groups.mapValues { (_, children) ->
                children.mapNotNull { it.path.lastOrNull() }.toSet()
            }
        } else {
            Log.w(TAG, "No claims metadata available, using hardcoded parent-to-children mapping")
            mapOf(
                "credential_holder" to setOf("family_name", "given_name", "birth_date"),
                "competent_institution" to setOf("country_code", "institution_id", "institution_name")
            )
        }

        // Build set of all claim names to include (parent + all its children)
        val claimsToInclude = mutableSetOf<String>()
        for (parent in requestedParents) {
            claimsToInclude.add(parent)  // Include the parent disclosure
            parentToChildren[parent]?.let { children ->
                claimsToInclude.addAll(children)  // Include ALL nested disclosures
            }
        }

        // Select matching disclosures
        val selectedDisclosures = allDisclosures.filter { disclosure ->
            claimsToInclude.contains(disclosure.claimName)
        }

        Log.d(TAG, "Selected ${selectedDisclosures.size} disclosures: ${selectedDisclosures.map { it.claimName }}")
        return RequestedClaimsResult(selectedDisclosures, resolver)
    }

    private fun parseSDJWT(storedCredential: String): List<Disclosure> {
        return try {
            SDJWT.parse(storedCredential).disclosures
        } catch (e: Exception) {
            throw RuntimeException("Error parsing SD-JWT: ${e.message}", e)
        }
    }

    /**
     * Create an SD-JWT Verifiable Presentation with selective disclosure and Key Binding JWT.
     *
     * @param credential  The stored SD-JWT credential string
     * @param disclosures Selected disclosures to include (parent + children)
     * @param clientId    The verifier's client_id (audience for KB-JWT)
     * @param nonce       The nonce from the authorization request
     * @return SD-JWT VP string (issuer JWT + disclosures + KB-JWT)
     */
    fun createVpToken(
        credential: String,
        disclosures: List<Disclosure> = listOf(),
        clientId: String,
        nonce: String
    ): String {
        Log.d(TAG, "Creating SD-JWT VP token with ${disclosures.size} disclosures")

        // Parse the provided credential into an SDJWT
        val vc = SDJWT.parse(credential)

        // If no disclosures are provided, include only the first one
        val disclosuresToUse = disclosures.ifEmpty { listOf(vc.disclosures[0]) }

        // Intended audience is the verifier's client_id
        val audience = listOf(clientId)

        // Create the binding JWT, part of the verifiable presentation
        val bindingJwt = createBindingJwt(vc, disclosuresToUse, audience, nonce, walletKeyManager.getWuaKey())

        // Create and return a Verifiable Presentation (VP) in SD-JWT format
        val vp = SDJWT(vc.credentialJwt, disclosuresToUse, bindingJwt.serialize())

        Log.d(TAG, "SD-JWT VP token created successfully")
        return vp.toString()
    }

    @Throws(ParseException::class, JOSEException::class)
    private fun createBindingJwt(
        vc: SDJWT,
        disclosures: List<Disclosure>,
        audience: List<String>,
        nonce: String,
        signingKey: JWK
    ): SignedJWT {
        val header = createBindingJwtHeader(signingKey)
        val payload = createBindingJwtPayload(vc, disclosures, audience, nonce)
        val jwt = SignedJWT(header, JWTClaimsSet.parse(payload))
        val signer = AndroidKeystoreSigner(AppConfig.WUA_KEY_ALIAS)
        jwt.sign(signer)
        return jwt
    }

    private fun createBindingJwtHeader(signingKey: JWK): JWSHeader {
        val alg = JWSAlgorithm.parse(signingKey.algorithm.name)
        val kid = signingKey.keyID
        return JWSHeader.Builder(alg)
            .keyID(kid)
            .type(JOSEObjectType("kb+jwt"))
            .build()
    }

    private fun createBindingJwtPayload(
        vc: SDJWT,
        disclosures: List<Disclosure>,
        audience: List<String>,
        nonce: String
    ): Map<String, Any> {
        val payload = LinkedHashMap<String, Any>()
        payload["iat"] = System.currentTimeMillis() / 1000L
        payload["aud"] = audience
        payload["nonce"] = nonce
        payload["sd_hash"] = computeSdHash(vc, disclosures)
        return payload
    }

    private fun computeSdHash(vc: SDJWT, disclosures: List<Disclosure>): String {
        return SDJWT(vc.credentialJwt, disclosures).sdHash
    }
}
