package dev.kmandalas.wallet.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import dev.kmandalas.wallet.config.AppConfig
import dev.kmandalas.wallet.dto.AccessTokenResponse
import dev.kmandalas.wallet.dto.CredentialConfiguration
import dev.kmandalas.wallet.dto.CredentialIssuerMetadata
import dev.kmandalas.wallet.dto.CredentialOffer
import dev.kmandalas.wallet.dto.NonceResponse
import dev.kmandalas.wallet.dto.OAuthServerMetadata
import dev.kmandalas.wallet.dto.PushedAuthorizationResponse
import dev.kmandalas.wallet.dto.StoredCredential
import dev.kmandalas.wallet.model.IssuerSession
import dev.kmandalas.wallet.security.AndroidKeystoreSigner
import dev.kmandalas.wallet.security.DPoPManager
import dev.kmandalas.wallet.security.WalletKeyManager
import dev.kmandalas.wallet.security.getEncryptedPrefs
import dev.kmandalas.wallet.service.mdoc.MDocCredentialService
import dev.kmandalas.wallet.service.sdjwt.SdJwtCredentialService
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import java.util.Date

class IssuanceService(
    private val client: HttpClient,
    private val wiaService: WiaService? = null,
    private val context: Context,
    private val walletKeyManager: WalletKeyManager
) {
    companion object {
        private const val TAG = "IssuanceService"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val dpopManager = DPoPManager(walletKeyManager)
    private val sdJwtCredentialService = SdJwtCredentialService(client)

    // Encrypted SharedPreferences - lazily initialized when activity context is available
    private var _encryptedPrefs: SharedPreferences? = null
    private val encryptedPrefs: SharedPreferences
        get() = _encryptedPrefs ?: throw IllegalStateException(
            "IssuanceService not initialized with Activity. Call initWithActivity() first."
        )

    /**
     * Initialize encrypted preferences with a FragmentActivity context.
     * Must be called from an Activity before using storage-related methods.
     */
    suspend fun initWithActivity(activity: FragmentActivity) {
        _encryptedPrefs = getEncryptedPrefs(context, activity)
    }

    /**
     * Fetches issuer metadata from /.well-known/openid-credential-issuer.
     * Returns the parsed metadata including credential configurations with claim display info.
     */
    suspend fun fetchIssuerMetadata(
        issuerUrl: String = AppConfig.ISSUER_URL
    ): CredentialIssuerMetadata {
        val url = buildWellKnownUrl(issuerUrl, "openid-credential-issuer")
        Log.d(TAG, "Fetching issuer metadata from: $url")
        val response: HttpResponse = client.get(url)
        val bodyText = response.bodyAsText()
        Log.d(TAG, "Issuer metadata response (${response.status}): $bodyText")
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        return json.decodeFromString(bodyText)
    }

    /**
     * Fetch OAuth Authorization Server metadata.
     * Tries RFC 8414 (.well-known/oauth-authorization-server) first,
     * falls back to OpenID Connect Discovery (.well-known/openid-configuration).
     */
    suspend fun fetchOAuthServerMetadata(authServerUrl: String): OAuthServerMetadata {
        val rfc8414Url = buildWellKnownUrl(authServerUrl, "oauth-authorization-server")
        Log.d(TAG, "Fetching AS metadata from (RFC 8414): $rfc8414Url")
        val rfc8414Response: HttpResponse = client.get(rfc8414Url)
        if (rfc8414Response.status.value in 200..299) {
            return rfc8414Response.body()
        }
        Log.d(TAG, "RFC 8414 returned ${rfc8414Response.status}, falling back to openid-configuration")
        val oidcUrl = buildWellKnownUrl(authServerUrl, "openid-configuration")
        Log.d(TAG, "Fetching AS metadata from (OIDC): $oidcUrl")
        val oidcResponse: HttpResponse = client.get(oidcUrl)
        return oidcResponse.body()
    }

    /**
     * Build a .well-known URL following RFC 8414 rules.
     * For "https://example.com/path/", produces "https://example.com/.well-known/suffix/path/".
     * For "https://example.com" (no path), produces "https://example.com/.well-known/suffix".
     */
    private fun buildWellKnownUrl(baseUrl: String, wellKnownSuffix: String): String {
        val uri = java.net.URI.create(baseUrl.trim())
        val path = uri.path  // e.g., "/test/a/kmandalas-wallet-1/" or ""
        return if (path.isNullOrEmpty() || path == "/") {
            "${uri.scheme}://${uri.authority}/.well-known/$wellKnownSuffix"
        } else {
            "${uri.scheme}://${uri.authority}/.well-known/$wellKnownSuffix$path"
        }
    }

    /**
     * Resolve a credential offer into a full IssuerSession by discovering
     * issuer metadata and AS metadata.
     * Accepts optional pre-fetched issuer metadata to avoid redundant network calls.
     */
    suspend fun resolveCredentialOffer(
        offer: CredentialOffer,
        prefetchedIssuerMeta: CredentialIssuerMetadata? = null
    ): IssuerSession {
        Log.d(TAG, "Resolving credential offer from: ${offer.credential_issuer}")

        // 1. Use pre-fetched metadata or fetch it
        val issuerMeta = prefetchedIssuerMeta ?: fetchIssuerMetadata(offer.credential_issuer)

        // 2. Determine AS URL (from metadata or grant, fallback to issuer URL)
        val authServerUrl = offer.grants?.authorization_code?.authorization_server
            ?: issuerMeta.authorization_servers?.firstOrNull()
            ?: offer.credential_issuer

        // 3. Fetch AS metadata
        val asMeta = fetchOAuthServerMetadata(authServerUrl)

        // 4. Determine scope from credential configurations
        // Prefer explicit scope field, then vct, then configId
        val scope = offer.credential_configuration_ids.firstOrNull()?.let { configId ->
            issuerMeta.credential_configurations_supported[configId]?.let { config ->
                config.scope ?: config.vct ?: configId
            }
        }

        // 5. Check if WIA is needed
        val sendWia = asMeta.token_endpoint_auth_methods_supported
            ?.contains("attest_jwt_client_auth") ?: false

        // 6. Check if AS supports authorization_details (RAR)
        val useAuthorizationDetails = asMeta.authorization_details_types_supported
            ?.contains("openid_credential") ?: false

        // 7. Extract credential display name from metadata
        val displayName = offer.credential_configuration_ids.firstOrNull()?.let { configId ->
            issuerMeta.credential_configurations_supported[configId]?.resolvedDisplay()?.firstOrNull()?.name
        }

        return IssuerSession(
            credentialIssuerUrl = offer.credential_issuer,
            credentialEndpoint = issuerMeta.credential_endpoint
                ?: "${offer.credential_issuer}/credential",
            nonceEndpoint = issuerMeta.nonce_endpoint
                ?: "${offer.credential_issuer}/credential/nonce",
            tokenEndpoint = asMeta.token_endpoint,
            authorizationEndpoint = asMeta.authorization_endpoint,
            parEndpoint = asMeta.pushed_authorization_request_endpoint,
            credentialConfigurationIds = offer.credential_configuration_ids,
            scope = scope,
            issuerState = offer.grants?.authorization_code?.issuer_state,
            authServerIssuer = asMeta.issuer,
            sendWia = sendWia,
            credentialDisplayName = displayName,
            useAuthorizationDetails = useAuthorizationDetails
        )
    }

    /**
     * Get all stored credential keys from the credential index.
     */
    fun getAllStoredCredentialKeys(): Set<String> {
        if (_encryptedPrefs == null) return emptySet()
        return encryptedPrefs.getStringSet(AppConfig.STORED_CREDENTIAL_INDEX, emptySet()) ?: emptySet()
    }

    /**
     * Get stored credential bundle from encrypted SharedPreferences.
     *
     * @param credentialKey The composite credential key (e.g., "pda1_sdjwt").
     * @return The deserialized StoredCredential, or null if not found
     */
    fun getStoredCredentialBundle(credentialKey: String): StoredCredential? {
        if (_encryptedPrefs == null) return null
        val storageKey = AppConfig.getCredentialStorageKey(credentialKey)
        val bundleJson = encryptedPrefs.getString(storageKey, null) ?: return null
        return try {
            json.decodeFromString(StoredCredential.serializer(), bundleJson)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse stored credential bundle for key: $credentialKey", e)
            null
        }
    }

    suspend fun exchangeAuthorizationCodeForToken(
        code: String,
        codeVerifier: String,
        redirectUri: String = "myapp://callback",
        tokenUrl: String = AppConfig.AUTH_SERVER_TOKEN_URL,
        authServerIssuer: String = AppConfig.AUTH_SERVER_ISSUER,
        sendWia: Boolean = true
    ): Result<AccessTokenResponse> {
        return try {
            val clientId = AppConfig.CLIENT_ID

            val response = sendTokenRequest(
                tokenUrl, clientId, code, codeVerifier, redirectUri,
                dpopNonce = null, authServerIssuer = authServerIssuer, sendWia = sendWia
            )

            // Handle use_dpop_nonce: retry with server-provided nonce (RFC 9449)
            if (response.status.value == 400) {
                val bodyText = response.bodyAsText()
                if (bodyText.contains("use_dpop_nonce")) {
                    val dpopNonce = response.headers["DPoP-Nonce"]
                    if (dpopNonce != null) {
                        Log.d(TAG, "Server requires DPoP nonce, retrying with nonce")
                        val retryResponse = sendTokenRequest(
                            tokenUrl, clientId, code, codeVerifier, redirectUri,
                            dpopNonce = dpopNonce, authServerIssuer = authServerIssuer, sendWia = sendWia
                        )
                        val accessTokenResponse = retryResponse.body<AccessTokenResponse>()
                        Log.d(TAG, "Token type: ${accessTokenResponse.token_type}")
                        return Result.success(accessTokenResponse)
                    }
                }
                return Result.failure(Exception("Token request failed: $bodyText"))
            }

            val accessTokenResponse = response.body<AccessTokenResponse>()
            Log.d(TAG, "Token type: ${accessTokenResponse.token_type}")
            Result.success(accessTokenResponse)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun sendTokenRequest(
        tokenUrl: String,
        clientId: String,
        code: String,
        codeVerifier: String,
        redirectUri: String,
        dpopNonce: String?,
        authServerIssuer: String,
        sendWia: Boolean
    ): HttpResponse {
        val dpopProof = dpopManager.createDPoPProof(
            httpMethod = "POST",
            httpUri = tokenUrl,
            nonce = dpopNonce
        )

        return client.submitForm(
            url = tokenUrl,
            formParameters = Parameters.build {
                append("grant_type", "authorization_code")
                append("code", code)
                append("redirect_uri", redirectUri)
                append("code_verifier", codeVerifier)
                append("client_id", clientId)
            }
        ) {
            header("DPoP", dpopProof)

            if (sendWia) {
                wiaService?.getStoredWia()?.let { wia ->
                    Log.d(TAG, "Adding WIA authentication headers")
                    val popJwt = wiaService.createPopJwt(authServerIssuer)
                    header("OAuth-Client-Attestation", wia)
                    header("OAuth-Client-Attestation-PoP", popJwt)
                }
            }
        }
    }

    /**
     * Pushes authorization request parameters to PAR endpoint.
     * Returns the request_uri to use in the authorization redirect.
     *
     * PAR (Pushed Authorization Request) enables early WIA validation
     * before the user sees the authorization screen.
     */
    suspend fun pushAuthorizationRequest(
        codeChallenge: String,
        codeChallengeMethod: String = "S256",
        parUrl: String = AppConfig.AUTH_SERVER_PAR_URL,
        scope: String = AppConfig.SCOPE,
        authServerIssuer: String = AppConfig.AUTH_SERVER_ISSUER,
        issuerState: String? = null,
        sendWia: Boolean = true,
        authorizationDetails: String? = null
    ): Result<String> {
        return try {
            val clientId = AppConfig.CLIENT_ID
            Log.d(TAG, "Pushing authorization request to PAR endpoint: $parUrl (scope=$scope, hasRAR=${authorizationDetails != null})")

            val response = client.submitForm(
                url = parUrl,
                formParameters = Parameters.build {
                    append("response_type", "code")
                    append("client_id", clientId)
                    append("scope", scope)
                    authorizationDetails?.let { append("authorization_details", it) }
                    append("redirect_uri", AppConfig.REDIRECT_URI)
                    append("code_challenge", codeChallenge)
                    append("code_challenge_method", codeChallengeMethod)
                    issuerState?.let { append("issuer_state", it) }
                }
            ) {
                // Add WIA authentication headers if WIA is available
                if (sendWia) {
                    wiaService?.getStoredWia()?.let { wia ->
                        Log.d(TAG, "Adding WIA authentication headers to PAR request")
                        val popJwt = wiaService.createPopJwt(authServerIssuer)
                        header("OAuth-Client-Attestation", wia)
                        header("OAuth-Client-Attestation-PoP", popJwt)
                    }
                }
            }

            if (response.status.isSuccess()) {
                val parResponse = response.body<PushedAuthorizationResponse>()
                Log.d(TAG, "PAR success: request_uri=${parResponse.requestUri}, expires_in=${parResponse.expiresIn}")
                Result.success(parResponse.requestUri)
            } else {
                val error = response.bodyAsText()
                Log.e(TAG, "PAR failed with status ${response.status}: $error")
                Result.failure(Exception("PAR request failed: $error"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "PAR exception", e)
            Result.failure(e)
        }
    }

    /**
     * Build RFC 9396 authorization_details JSON for OID4VCI.
     * Each entry has type "openid_credential" and a credential_configuration_id.
     * When credentialIssuerUrl is provided, includes "locations" array per OID4VCI 5.1.1
     * (REQUIRED when issuer metadata contains authorization_servers).
     */
    fun buildAuthorizationDetails(
        credentialConfigurationIds: List<String>,
        credentialIssuerUrl: String? = null
    ): String {
        val details = credentialConfigurationIds.map { configId ->
            val locations = if (credentialIssuerUrl != null) {
                ""","locations":["$credentialIssuerUrl"]"""
            } else ""
            """{"type":"openid_credential","credential_configuration_id":"$configId"$locations}"""
        }
        return "[${details.joinToString(",")}]"
    }

    suspend fun getNonce(nonceUrl: String = "${AppConfig.ISSUER_URL}/credential/nonce"): String {
        val response: NonceResponse = client.post(nonceUrl).body()
        return response.c_nonce
    }

    /**
     * Create JWT proof for credential request.
     * Uses WUA key (attested) and includes key_attestation header with WUA JWT.
     *
     * @param nonce The nonce from the issuer
     * @param wuaJwt The Wallet Unit Attestation JWT (from encrypted SharedPreferences)
     * @return Serialized JWT proof string
     */
    fun createJwtProof(
        nonce: String,
        wuaJwt: String?,
        audience: String = AppConfig.ISSUER_URL
    ): String {
        val headerBuilder = JWSHeader.Builder(JWSAlgorithm.ES256)
            .type(JOSEObjectType("openid4vci-proof+jwt"))

        if (wuaJwt != null) {
            // HAIP mode: use key_attestation (WUA) with kid referencing attested_keys index
            Log.d("IssuanceService", "Creating JWT proof with WUA key_attestation")
            headerBuilder.keyID("0")
            headerBuilder.customParam("key_attestation", wuaJwt)
        } else {
            // Standard mode: include jwk directly in header
            Log.d("IssuanceService", "Creating JWT proof with jwk header")
            headerBuilder.jwk(walletKeyManager.getWalletKey().toPublicJWK())
        }

        val header = headerBuilder.build()

        val claims = JWTClaimsSet.Builder()
            .issuer("wallet-client")
            .audience(audience)
            .issueTime(Date())
            .expirationTime(Date(System.currentTimeMillis() + 300000))
            .claim("nonce", nonce)
            .build()

        val signedJWT = SignedJWT(header, claims)
        val keyAlias = if (wuaJwt != null) AppConfig.WUA_KEY_ALIAS else AppConfig.KEY_ALIAS
        val signer = AndroidKeystoreSigner(keyAlias)
        signedJWT.sign(signer)

        return signedJWT.serialize()
    }

    suspend fun requestCredential(
        accessToken: String,
        jwtProof: String,
        credentialConfig: CredentialConfiguration? = null,
        format: String = AppConfig.FORMAT_SD_JWT,
        credentialUrl: String = "${AppConfig.ISSUER_URL}/credential",
        credentialConfigId: String? = null,
        displayName: String? = null
    ): String {
        val resolvedConfigId = credentialConfigId ?: if (format == AppConfig.FORMAT_MSO_MDOC) {
            AppConfig.MDOC_CREDENTIAL_CONFIG_ID
        } else {
            AppConfig.SD_JWT_CREDENTIAL_CONFIG_ID
        }

        val requestBody = """
            {
              "credential_configuration_id": "$resolvedConfigId",
              "proofs": {
                "jwt": ["$jwtProof"]
              }
            }
        """.trimIndent()

        var response = sendCredentialRequest(credentialUrl, accessToken, requestBody, dpopNonce = null)

        // Handle use_dpop_nonce: retry with server-provided nonce (RFC 9449)
        if (response.status.value == 401) {
            val dpopNonce = response.headers["DPoP-Nonce"]
            if (dpopNonce != null) {
                Log.d(TAG, "Credential endpoint requires DPoP nonce, retrying")
                response = sendCredentialRequest(credentialUrl, accessToken, requestBody, dpopNonce)
            }
        }

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            throw Exception("Credential request failed (${response.status.value}): $errorBody")
        }

        val credential = extractCredentialFromResponse(response)

        // Verify and store the credential (with optional metadata)
        val isDynamicIssuer = credentialUrl != "${AppConfig.ISSUER_URL}/credential"
        if (isDynamicIssuer) {
            Log.d(TAG, "Dynamic issuer — skipping JWKS cross-validation")
        } else if (format == AppConfig.FORMAT_MSO_MDOC) {
            Log.d(TAG, "mDoc credential received, skipping client-side signature verification")
        } else {
            sdJwtCredentialService.verify(credential)
        }
        // Build effective config with display name for dynamic issuers
        val effectiveConfig = credentialConfig ?: displayName?.let {
            CredentialConfiguration(
                format = format,
                display = listOf(dev.kmandalas.wallet.dto.CredentialDisplay(name = it))
            )
        }
        storeCredential(credential, resolvedConfigId, effectiveConfig, format)

        return credential
    }

    /**
     * Extract credential from response, supporting both OID4VCI 1.0 Final format
     * (credentials array of objects) and legacy flat format.
     */
    private suspend fun sendCredentialRequest(
        credentialUrl: String,
        accessToken: String,
        requestBody: String,
        dpopNonce: String?
    ): HttpResponse {
        val dpopProof = dpopManager.createDPoPProof(
            httpMethod = "POST",
            httpUri = credentialUrl,
            accessTokenHash = dpopManager.computeAccessTokenHash(accessToken),
            nonce = dpopNonce
        )
        return client.post(credentialUrl) {
            contentType(ContentType.Application.Json)
            header("Authorization", "DPoP $accessToken")
            header("DPoP", dpopProof)
            setBody(requestBody)
        }
    }

    private suspend fun extractCredentialFromResponse(response: HttpResponse): String {
        val body = response.bodyAsText()
        val json = org.json.JSONObject(body)

        // OID4VCI 1.0 Final: {"credentials": [{"credential": "eyJ..."}]}
        val credentials = json.optJSONArray("credentials")
        if (credentials != null && credentials.length() > 0) {
            val first = credentials.getJSONObject(0)
            val cred = first.optString("credential", null)
            if (!cred.isNullOrEmpty()) return cred
        }

        // Legacy fallback: {"credential": "eyJ..."}
        val credential = json.optString("credential", null)
        if (!credential.isNullOrEmpty()) return credential

        throw Exception("Failed to extract credential from response: $body")
    }

    /**
     * Store credential as a single JSON bundle in encrypted SharedPreferences.
     * Maintains a credential index set for enumeration.
     */
    fun storeCredential(
        credential: String,
        credentialConfigurationId: String,
        credentialConfig: CredentialConfiguration? = null,
        format: String = AppConfig.FORMAT_SD_JWT
    ) {
        val credentialKey = AppConfig.extractCredentialKey(credentialConfigurationId)
        val (issuedAt, expiresAt) = extractCredentialDates(credential, format)
        val bundle = StoredCredential(
            rawCredential = credential,
            format = format,
            claimsMetadata = credentialConfig?.resolvedClaims(),
            displayMetadata = credentialConfig?.resolvedDisplay(),
            issuedAt = issuedAt,
            expiresAt = expiresAt
        )
        val storageKey = AppConfig.getCredentialStorageKey(credentialKey)
        val bundleJson = json.encodeToString(StoredCredential.serializer(), bundle)
        encryptedPrefs.edit { putString(storageKey, bundleJson) }
        // Maintain the credential index
        val currentIndex = encryptedPrefs.getStringSet(AppConfig.STORED_CREDENTIAL_INDEX, emptySet())?.toMutableSet() ?: mutableSetOf()
        currentIndex.add(credentialKey)
        encryptedPrefs.edit { putStringSet(AppConfig.STORED_CREDENTIAL_INDEX, currentIndex) }
        Log.d(TAG, "Credential stored successfully with key: $credentialKey, format: $format")
    }

    /**
     * Remove stored credential from encrypted SharedPreferences.
     *
     * @param credentialKey The composite credential key (e.g., "pda1_sdjwt").
     */
    fun removeCredential(credentialKey: String) {
        encryptedPrefs.edit { remove(AppConfig.getCredentialStorageKey(credentialKey)) }
        // Update index
        val currentIndex = encryptedPrefs.getStringSet(AppConfig.STORED_CREDENTIAL_INDEX, emptySet())?.toMutableSet() ?: mutableSetOf()
        currentIndex.remove(credentialKey)
        encryptedPrefs.edit { putStringSet(AppConfig.STORED_CREDENTIAL_INDEX, currentIndex) }
        Log.d(TAG, "Credential removed for key: $credentialKey")
    }

    /**
     * Decodes a credential, dispatching based on format.
     * For SD-JWT: extracts disclosed claims from disclosures.
     * For mDoc: decodes CBOR IssuerSigned structure to extract claims.
     */
    fun decodeCredential(credential: String, format: String): Map<String, Any> {
        return if (format == AppConfig.FORMAT_MSO_MDOC) {
            MDocCredentialService.decode(credential).claims
        } else {
            sdJwtCredentialService.decode(credential)
        }
    }

    /**
     * Extract issuance and expiration dates from a credential.
     * For SD-JWT: reads iat/exp from the JWT payload.
     * For mDoc: extracted from ValidityInfo during decode (single CBOR parse).
     * Returns (issuedAtEpochSeconds, expiresAtEpochSeconds).
     */
    private fun extractCredentialDates(credential: String, format: String): Pair<Long?, Long?> {
        return try {
            if (format == AppConfig.FORMAT_MSO_MDOC) {
                val result = MDocCredentialService.decode(credential)
                result.issuedAt to result.expiresAt
            } else {
                val jwtPart = credential.substringBefore("~")
                val signedJwt = SignedJWT.parse(jwtPart)
                val claims = signedJwt.jwtClaimsSet
                val iat = claims.issueTime?.time?.div(1000)
                val exp = claims.expirationTime?.time?.div(1000)
                iat to exp
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract credential dates", e)
            null to null
        }
    }

    /**
     * Flattens nested claims for display purposes.
     * Converts nested Map structures to dot-notation keys.
     */
    @Suppress("UNCHECKED_CAST")
    fun flattenClaimsForDisplay(claims: Map<String, Any>): Map<String, String> {
        val flattened = mutableMapOf<String, String>()
        for ((key, value) in claims) {
            if (value is Map<*, *>) {
                val nested = value as Map<String, Any>
                for ((nestedKey, nestedValue) in nested) {
                    flattened["$key.$nestedKey"] = nestedValue.toString()
                }
            } else {
                flattened[key] = value.toString()
            }
        }
        return flattened
    }

}