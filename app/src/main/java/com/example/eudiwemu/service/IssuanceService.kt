package com.example.eudiwemu.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import com.example.eudiwemu.config.AppConfig
import com.example.eudiwemu.dto.AccessTokenResponse
import com.example.eudiwemu.dto.CredentialConfiguration
import com.example.eudiwemu.dto.CredentialIssuerMetadata
import com.example.eudiwemu.dto.NonceResponse
import com.example.eudiwemu.dto.PushedAuthorizationResponse
import com.example.eudiwemu.dto.StoredCredential
import com.example.eudiwemu.security.AndroidKeystoreSigner
import com.example.eudiwemu.security.DPoPManager
import com.example.eudiwemu.security.WalletKeyManager
import com.example.eudiwemu.security.getEncryptedPrefs
import com.example.eudiwemu.service.mdoc.MDocCredentialService
import com.example.eudiwemu.service.sdjwt.SdJwtCredentialService
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.basicAuth
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

    walletKeyManager: WalletKeyManager
    ) {
    companion object {
        private const val TAG = "IssuanceService"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val dpopManager = DPoPManager(walletKeyManager)
    private val sdJwtCredentialService = SdJwtCredentialService(client)

    // Encrypted SharedPreferences - lazily initialized when activity context is available
    private var _encryptedPrefs: SharedPreferences? = null

    /**
     * Initialize encrypted preferences with a FragmentActivity context.
     * Must be called from an Activity before using storage-related methods.
     */
    suspend fun initWithActivity(activity: FragmentActivity) {
        _encryptedPrefs = getEncryptedPrefs(context, activity)
    }

    private val encryptedPrefs: SharedPreferences
        get() = _encryptedPrefs ?: throw IllegalStateException(
            "IssuanceService not initialized with Activity. Call initWithActivity() first."
        )

    /**
     * Fetches issuer metadata from /.well-known/openid-credential-issuer.
     * Returns the parsed metadata including credential configurations with claim display info.
     */
    suspend fun fetchIssuerMetadata(): CredentialIssuerMetadata {
        val url = "${AppConfig.ISSUER_URL}/.well-known/openid-credential-issuer"
        val response: HttpResponse = client.get(url)
        return response.body()
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

    @Deprecated("replaced client_credentials with authorization_code flow")
    suspend fun obtainAccessToken(): AccessTokenResponse {
        val response: AccessTokenResponse = client.post(AppConfig.AUTH_SERVER_TOKEN_URL) {
            contentType(ContentType.Application.FormUrlEncoded)
            basicAuth(AppConfig.CLIENT_ID, AppConfig.CLIENT_SECRET)
            setBody("grant_type=client_credentials&scope=openid vc:issue")
        }.body()

        return response
    }

    suspend fun exchangeAuthorizationCodeForToken(
        code: String,
        codeVerifier: String,
        redirectUri: String = "myapp://callback"
    ): Result<String> {
        return try {
            val tokenUrl = AppConfig.AUTH_SERVER_TOKEN_URL
            val clientId = AppConfig.CLIENT_ID

            val dpopProof = dpopManager.createDPoPProof(
                httpMethod = "POST",
                httpUri = tokenUrl
            )

            val response = client.submitForm(
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

                // Add WIA authentication headers if WIA is available
                wiaService?.getStoredWia()?.let { wia ->
                    Log.d("IssuanceService", "Adding WIA authentication headers")
                    val popJwt = wiaService.createPopJwt(AppConfig.AUTH_SERVER_ISSUER)
                    header("OAuth-Client-Attestation", wia)
                    header("OAuth-Client-Attestation-PoP", popJwt)
                }
            }

            val accessTokenResponse = response.body<AccessTokenResponse>()
            Log.d("IssuanceService", "Token type: ${accessTokenResponse.token_type}")
            Result.success(accessTokenResponse.access_token)
        } catch (e: Exception) {
            Result.failure(e)
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
        codeChallengeMethod: String = "S256"
    ): Result<String> {
        return try {
            val parUrl = AppConfig.AUTH_SERVER_PAR_URL
            val clientId = AppConfig.CLIENT_ID
            Log.d(TAG, "Pushing authorization request to PAR endpoint: $parUrl")

            val response = client.submitForm(
                url = parUrl,
                formParameters = Parameters.build {
                    append("response_type", "code")
                    append("client_id", clientId)
                    append("scope", AppConfig.SCOPE)
                    append("redirect_uri", AppConfig.REDIRECT_URI)
                    append("code_challenge", codeChallenge)
                    append("code_challenge_method", codeChallengeMethod)
                }
            ) {
                // Add WIA authentication headers if WIA is available
                wiaService?.getStoredWia()?.let { wia ->
                    Log.d(TAG, "Adding WIA authentication headers to PAR request")
                    val popJwt = wiaService.createPopJwt(AppConfig.AUTH_SERVER_ISSUER)
                    header("OAuth-Client-Attestation", wia)
                    header("OAuth-Client-Attestation-PoP", popJwt)
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

    suspend fun getNonce(accessToken: String): String {
        val nonceUrl = "${AppConfig.ISSUER_URL}/credential/nonce"

        val dpopProof = dpopManager.createDPoPProof(
            httpMethod = "GET",
            httpUri = nonceUrl,
            accessTokenHash = dpopManager.computeAccessTokenHash(accessToken)
        )

        val response: NonceResponse = client.get(nonceUrl) {
            header("Authorization", "DPoP $accessToken")
            header("DPoP", dpopProof)
        }.body()

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
    fun createJwtProof(nonce: String, wuaJwt: String): String {
        Log.d("IssuanceService", "Creating JWT proof with WUA key_attestation")

        val header = JWSHeader.Builder(JWSAlgorithm.ES256)
            .type(JOSEObjectType("openid4vci-proof+jwt"))
            .keyID("0")  // Index into attested_keys in WUA
            .customParam("key_attestation", wuaJwt)
            .build()

        val claims = JWTClaimsSet.Builder()
            .issuer("wallet-client")
            .audience(AppConfig.ISSUER_URL)
            .issueTime(Date())
            .expirationTime(Date(System.currentTimeMillis() + 300000))
            .claim("nonce", nonce)
            .build()

        val signedJWT = SignedJWT(header, claims)
        val signer = AndroidKeystoreSigner(AppConfig.WUA_KEY_ALIAS)  // WUA key (attested)
        signedJWT.sign(signer)

        return signedJWT.serialize()
    }

    suspend fun requestCredential(
        accessToken: String,
        jwtProof: String,
        credentialConfig: CredentialConfiguration? = null,
        format: String = AppConfig.FORMAT_SD_JWT
    ): String {
        val credentialUrl = "${AppConfig.ISSUER_URL}/credential"

        val dpopProof = dpopManager.createDPoPProof(
            httpMethod = "POST",
            httpUri = credentialUrl,
            accessTokenHash = dpopManager.computeAccessTokenHash(accessToken)
        )

        val credentialConfigId = if (format == AppConfig.FORMAT_MSO_MDOC) {
            AppConfig.MDOC_CREDENTIAL_CONFIG_ID
        } else {
            AppConfig.SD_JWT_CREDENTIAL_CONFIG_ID
        }

        val requestBody = """
            {
              "format": "$format",
              "credentialConfigurationId": "$credentialConfigId",
              "proof": {
                "proofType": "jwt",
                "jwt": "$jwtProof"
              }
            }
        """.trimIndent()

        val response: HttpResponse = client.post(credentialUrl) {
            contentType(ContentType.Application.Json)
            header("Authorization", "DPoP $accessToken")
            header("DPoP", dpopProof)
            setBody(requestBody)
        }
        val jsonResponse: Map<String, String> = response.body()
        val credential = jsonResponse["credential"] ?: throw Exception("Failed to request credential")

        // Verify and store the credential (with optional metadata)
        if (format == AppConfig.FORMAT_MSO_MDOC) {
            Log.d(TAG, "mDoc credential received, skipping client-side signature verification")
        } else {
            sdJwtCredentialService.verify(credential)
        }
        storeCredential(credential, credentialConfigId, credentialConfig, format)

        return credential
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