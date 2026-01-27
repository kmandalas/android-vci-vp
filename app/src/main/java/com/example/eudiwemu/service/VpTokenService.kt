package com.example.eudiwemu.service

import android.util.Log
import com.authlete.sd.Disclosure
import com.authlete.sd.SDJWT
import com.example.eudiwemu.config.AppConfig
import com.example.eudiwemu.dto.AuthorizationRequestResponse
import com.example.eudiwemu.security.AndroidKeystoreSigner
import com.example.eudiwemu.security.WalletKeyManager
import com.nimbusds.jose.EncryptionMethod
import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWEAlgorithm
import com.nimbusds.jose.JWEHeader
import com.nimbusds.jose.JWEObject
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.ECDHEncrypter
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.util.Base64
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.text.ParseException

class VpTokenService(
    private val client: HttpClient,
    private val walletKeyManager: WalletKeyManager
) {
    companion object {
        private const val TAG = "VpTokenService"
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Fetches and verifies the signed Authorization Request (JAR) from the request URI.
     * Validates the JWT signature using the x5c certificate and verifies x509_hash client_id.
     *
     * Security: The x509_hash in client_id acts as key pinning - it's the SHA-256 hash of the
     * verifier's signing certificate. This provides similar protection to JWKS cross-checking.
     * Note: client_metadata.jwks contains the verifier's encryption key (ephemeral, per-request),
     * which is different from the signing key in x5c, so JWKS cross-check is not applicable here.
     */
    suspend fun getRequestObject(requestUri: String, expectedClientId: String): AuthorizationRequestResponse {
        // Request the signed authorization request (JAR) with proper Accept header
        val jwtString: String = client.get(requestUri) {
            accept(ContentType("application", "oauth-authz-req+jwt"))
        }.body()

        // Parse the signed JWT
        val signedJwt = SignedJWT.parse(jwtString)
        val header = signedJwt.header

        // Extract x5c certificate chain from header
        val x5cChain = header.x509CertChain
            ?: throw IllegalArgumentException("Missing x5c header in authorization request JWT")

        if (x5cChain.isEmpty()) {
            throw IllegalArgumentException("Empty x5c certificate chain")
        }

        // Parse the first certificate (verifier's certificate)
        val certBytes = x5cChain[0].decode()
        val certFactory = CertificateFactory.getInstance("X.509")
        val certificate = certFactory.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate

        // Verify JWT signature using the certificate's public key
        val publicKey = certificate.publicKey as ECPublicKey
        val verifier = ECDSAVerifier(publicKey)

        if (!signedJwt.verify(verifier)) {
            throw SecurityException("Invalid JWT signature - authorization request not trusted")
        }

        Log.d(TAG, "JAR signature verified successfully")

        // Compute x509_hash from the certificate and validate against client_id
        val computedHash = computeX509Hash(certificate)
        val expectedHash = if (expectedClientId.startsWith("x509_hash:")) {
            expectedClientId.substringAfter("x509_hash:")
        } else {
            expectedClientId
        }

        if (computedHash != expectedHash) {
            throw SecurityException("x509_hash mismatch: computed=$computedHash, expected=$expectedHash")
        }

        Log.d(TAG, "x509_hash validated: $computedHash")

        // Extract claims from the JWT payload (already JSON)
        val payloadJson = signedJwt.payload.toString()

        return json.decodeFromString<AuthorizationRequestResponse>(payloadJson)
    }

    /**
     * Computes the x509_hash (SHA-256 of DER-encoded certificate, base64url encoded without padding).
     */
    private fun computeX509Hash(certificate: X509Certificate): String {
        val derEncoded = certificate.encoded
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(derEncoded)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
    }

    suspend fun sendVpTokenToVerifier(
        vpToken: String,
        responseUri: String,
        request: AuthorizationRequestResponse
    ): String {
        try {
            Log.d(TAG, "response_mode='${request.response_mode}', " +
                    "has_jwks=${request.client_metadata?.jwks != null}")

            // Per OpenID4VP spec, both direct_post and direct_post.jwt use form-urlencoded
            val response: HttpResponse = if (request.response_mode == "direct_post.jwt") {
                // Encrypt the VP response for direct_post.jwt mode
                val encryptedResponse = encryptVpResponse(vpToken, request)
                client.post(responseUri) {
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("response=${java.net.URLEncoder.encode(encryptedResponse, "UTF-8")}")
                }
            } else {
                // Plain direct_post mode
                client.post(responseUri) {
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("vp_token=${java.net.URLEncoder.encode(vpToken, "UTF-8")}")
                }
            }

            val responseBody: String = response.body()

            Log.d(TAG, "VP Verification Response: $responseBody")
            return responseBody
        } catch (e: Exception) {
            Log.e(TAG, "Error sending VP Token to Verifier", e)
            throw e
        }
    }

    /**
     * Encrypts the VP response as JWE using the verifier's public key from client_metadata.
     */
    private fun encryptVpResponse(vpToken: String, request: AuthorizationRequestResponse): String {
        val clientMetadata = request.client_metadata
            ?: throw IllegalArgumentException("client_metadata required for direct_post.jwt")

        val jwks = clientMetadata.jwks
            ?: throw IllegalArgumentException("jwks required in client_metadata for encryption")

        // Parse the first encryption key from JWKS
        val keyJson = jwks.keys.firstOrNull()
            ?: throw IllegalArgumentException("No keys in JWKS")

        val verifierPublicKey = ECKey.parse(keyJson.toString())

        // Determine encryption method (prefer A256GCM)
        val encMethod = when (clientMetadata.authorization_encrypted_response_enc) {
            "A128GCM" -> EncryptionMethod.A128GCM
            else -> EncryptionMethod.A256GCM
        }

        // Build JWE header with kid if available
        val headerBuilder = JWEHeader.Builder(JWEAlgorithm.ECDH_ES, encMethod)
        verifierPublicKey.keyID?.let { headerBuilder.keyID(it) }
        val header = headerBuilder.build()

        // Create payload with vp_token and state
        // For DCQL, vp_token must be a map of credential IDs to tokens
        val credentialId = request.dcql_query.credentials.firstOrNull()?.id ?: "credential"
        Log.d(TAG, "Using credential ID: $credentialId, state: ${request.state}")

        // Build JSON payload - vp_token is Map<QueryId, List<VP>>
        // e.g. {"vp_token":{"query_0":["eyJ..."]},"state":"xyz"}
        val stateJson = request.state?.let { ""","state":"$it"""" } ?: ""
        val payloadJson = """{"vp_token":{"$credentialId":["$vpToken"]}$stateJson}"""

        Log.d(TAG, "Encrypted payload structure: vp_token={$credentialId:...}, " +
                "state=${request.state != null}")
        val payload = Payload(payloadJson)

        // Create and encrypt JWE
        val jweObject = JWEObject(header, payload)
        val encrypter = ECDHEncrypter(verifierPublicKey)
        jweObject.encrypt(encrypter)

        Log.d(TAG, "VP response encrypted with ECDH-ES + $encMethod")
        return jweObject.serialize()
    }

    /**
     * Extracts disclosures for requested claims with two-level recursive disclosure support.
     *
     * When a parent claim is requested (e.g., "credential_holder"), this method includes:
     * - The parent disclosure itself (contains nested _sd array)
     * - ALL nested child disclosures (family_name, given_name, birth_date)
     *
     * This matches EU Reference Demo behavior where selecting a parent reveals all its children.
     */
    fun extractRequestedClaims(
        request: AuthorizationRequestResponse,
        storedCredential: String
    ): List<Disclosure> {
        val jwtPart = storedCredential.substringBefore("~")
        val signedJwt = SignedJWT.parse(jwtPart)
        val existingVct = signedJwt.jwtClaimsSet.getStringClaim("vct")

        // DCQL: Get the first credential query (assuming single credential request)
        val credentialQuery = request.dcql_query.credentials.firstOrNull()
            ?: throw IllegalArgumentException("No credential query in DCQL request")

        // Validate VCT matches
        val requestedVcts = credentialQuery.meta?.vct_values
        if (requestedVcts != null && existingVct !in requestedVcts) {
            throw IllegalArgumentException("❌ VCT mismatch: expected $requestedVcts, found '$existingVct'")
        }

        // Extract requested parent claim names from DCQL paths
        val requestedParents = credentialQuery.claims
            ?.mapNotNull { it.path.firstOrNull() }
            ?.toSet() ?: emptySet()

        Log.d(TAG, "Requested parent claims: $requestedParents")

        val allDisclosures = parseSDJWT(storedCredential)
        Log.d(TAG, "Available disclosures: ${allDisclosures.map { it.claimName }}")

        // Mapping of parent -> nested claim names (two-level disclosure structure)
        val parentToChildren = mapOf(
            "credential_holder" to setOf("family_name", "given_name", "birth_date"),
            "competent_institution" to setOf("country_code", "institution_id", "institution_name")
        )

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
        return selectedDisclosures
    }

    private fun parseSDJWT(storedCredential: String): List<Disclosure> {
        return try {
            SDJWT.parse(storedCredential).disclosures
        } catch (e: Exception) {
            throw RuntimeException("Error parsing SD-JWT: ${e.message}", e)
        }
    }


    // Function to create Verifiable Presentation (VP)
    fun createVP(
        credential: String,
        disclosures: List<Disclosure> = listOf(),
        clientId: String,
        nonce: String
    ): SDJWT {
        // Parse the provided credential into an SDJWT
        val vc = SDJWT.parse(credential)

        // If no disclosures are provided, include only the first one
        val disclosuresToUse = disclosures.ifEmpty { listOf(vc.disclosures[0]) }

        // Intended audience is the verifier's client_id
        val audience = listOf(clientId)

        // Create the binding JWT, part of the verifiable presentation
        val bindingJwt = createBindingJwt(vc, disclosuresToUse, audience, nonce, walletKeyManager.getWuaKey())

        // Create and return a Verifiable Presentation (VP) in SD-JWT format
        return SDJWT(vc.credentialJwt, disclosuresToUse, bindingJwt.serialize())
    }

    // Function to create the binding JWT
    @Throws(ParseException::class, JOSEException::class)
    private fun createBindingJwt(
        vc: SDJWT,
        disclosures: List<Disclosure>,
        audience: List<String>,
        nonce: String,
        signingKey: JWK
    ): SignedJWT {
        // Create the header part of a binding JWT
        val header = createBindingJwtHeader(signingKey)

        // Create the payload part of a binding JWT
        val payload = createBindingJwtPayload(vc, disclosures, audience, nonce)

        // Create a binding JWT (not signed yet)
        val jwt = SignedJWT(header, JWTClaimsSet.parse(payload))

        // Create a signer
        val signer = AndroidKeystoreSigner(AppConfig.WUA_KEY_ALIAS)

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
    private fun createBindingJwtPayload(
        vc: SDJWT,
        disclosures: List<Disclosure>,
        audience: List<String>,
        nonce: String
    ): Map<String, Any> {
        val payload = LinkedHashMap<String, Any>()

        // "iat" - The issuance time of the binding JWT
        payload["iat"] = System.currentTimeMillis() / 1000L

        // "aud" - The intended receiver of the binding JWT (verifier's client_id)
        payload["aud"] = audience

        // "nonce" - The nonce from the authorization request (ensures freshness)
        payload["nonce"] = nonce

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