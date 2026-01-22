package com.example.eudiwemu.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.example.eudiwemu.config.AppConfig
import com.example.eudiwemu.dto.WiaCredentialRequest
import com.example.eudiwemu.dto.WiaCredentialResponse
import com.example.eudiwemu.dto.WiaNonceResponse
import com.example.eudiwemu.dto.WiaProof
import com.example.eudiwemu.security.AndroidKeystoreSigner
import com.example.eudiwemu.security.WalletKeyManager
import com.example.eudiwemu.security.getEncryptedPrefs
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.util.Date
import java.util.UUID

/**
 * Service responsible for Wallet Instance Attestation (WIA) issuance and PoP JWT generation.
 *
 * Per draft-ietf-oauth-attestation-based-client-auth, WIA proves the wallet instance
 * is authentic to the Authorization Server. The WIA contains the wallet's public key
 * in the cnf.jwk claim, and the wallet proves possession by signing a PoP JWT.
 *
 * Key architecture:
 * - WIA uses the Wallet Key (same key used for DPoP)
 * - WIA proof JWT is sent to Wallet Provider to obtain the WIA
 * - WIA PoP JWT is sent to Auth Server with each token request
 *
 * Flow:
 * 1. Get nonce from Wallet Provider
 * 2. Create proof JWT signed with Wallet Key
 * 3. Submit proof to get WIA credential
 * 4. Store WIA in encrypted SharedPreferences
 * 5. For each token request, create PoP JWT and include WIA headers
 */
class WiaService(
    private val client: HttpClient,
    private val walletKeyManager: WalletKeyManager,
    private val context: Context
) {
    companion object {
        private const val TAG = "WiaService"
    }

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
            "WiaService not initialized with Activity. Call initWithActivity() first."
        )

    /**
     * Get a fresh nonce from the Wallet Provider for WIA request.
     */
    suspend fun getNonce(): WiaNonceResponse {
        Log.d(TAG, "Requesting WIA nonce from Wallet Provider")
        val response: WiaNonceResponse = client.get(AppConfig.WALLET_PROVIDER_WIA_NONCE_URL).body()
        Log.d(TAG, "Received WIA nonce: ${response.cNonce}")
        return response
    }

    /**
     * Create JWT proof for WIA credential request.
     * The proof demonstrates possession of the Wallet Key.
     *
     * JWT structure:
     * - Header: typ="openid4vci-proof+jwt", alg="ES256", jwk=<wallet public key>
     * - Payload: iss=client_id, aud=wallet_provider_url, iat, exp, nonce
     * - Signed by: Wallet Key
     *
     * @param nonce The nonce from the Wallet Provider
     * @return Serialized JWT proof string
     */
    fun createWiaProof(nonce: String): String {
        Log.d(TAG, "Creating WIA JWT proof with Wallet Key")

        val ecKey = walletKeyManager.getWalletKey()
        val header = JWSHeader.Builder(JWSAlgorithm.ES256)
            .type(JOSEObjectType("openid4vci-proof+jwt"))
            .jwk(ecKey.toPublicJWK())
            .build()

        val now = Date()
        val claims = JWTClaimsSet.Builder()
            .issuer(AppConfig.CLIENT_ID)
            .audience(AppConfig.WALLET_PROVIDER_URL)
            .issueTime(now)
            .expirationTime(Date(now.time + 300000)) // 5 minutes
            .claim("nonce", nonce)
            .build()

        val signedJWT = SignedJWT(header, claims)
        val signer = AndroidKeystoreSigner(AppConfig.KEY_ALIAS) // Wallet Key
        signedJWT.sign(signer)

        Log.d(TAG, "WIA JWT proof created successfully")
        return signedJWT.serialize()
    }

    /**
     * Request WIA credential from the Wallet Provider.
     *
     * @param jwtProof The signed JWT proof
     * @return WiaCredentialResponse containing the WIA JWT
     */
    suspend fun requestWiaCredential(jwtProof: String): Result<WiaCredentialResponse> {
        Log.d(TAG, "Requesting WIA credential")

        val request = WiaCredentialRequest(
            clientId = AppConfig.CLIENT_ID,
            proof = WiaProof(jwt = jwtProof)
        )

        return try {
            val response: HttpResponse = client.post(AppConfig.WALLET_PROVIDER_WIA_CREDENTIAL_URL) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }

            if (response.status.isSuccess()) {
                val rawBody = response.bodyAsText()
                Log.d(TAG, "WIA raw response: $rawBody")
                val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                val wiaResponse = json.decodeFromString(WiaCredentialResponse.serializer(), rawBody)
                Log.d(TAG, "WIA credential received. WIA ID: ${wiaResponse.wiaId}")
                Result.success(wiaResponse)
            } else {
                val errorBody = response.bodyAsText()
                Log.e(TAG, "WIA credential request failed: $errorBody")
                Result.failure(Exception("WIA issuance failed: $errorBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting WIA credential", e)
            Result.failure(e)
        }
    }

    /**
     * Verify the WIA credential signature using the Wallet Provider's JWKS.
     *
     * @param wiaJwt The WIA credential JWT to verify
     * @return true if signature is valid
     */
    suspend fun verifyWiaCredential(wiaJwt: String): Boolean {
        Log.d(TAG, "Verifying WIA credential signature")

        return try {
            val signedJwt = SignedJWT.parse(wiaJwt)
            val jwkSet = fetchWalletProviderJWKSet()
            val publicKey = jwkSet.keys.first().toECKey()
            val verifier = ECDSAVerifier(publicKey)
            val isValid = signedJwt.verify(verifier)
            Log.d(TAG, "WIA signature valid: $isValid")
            isValid
        } catch (e: Exception) {
            Log.e(TAG, "Error verifying WIA credential", e)
            false
        }
    }

    /**
     * Create PoP JWT for Authorization Server requests.
     *
     * Per draft-ietf-oauth-attestation-based-client-auth, the PoP JWT proves
     * possession of the key bound in the WIA's cnf.jwk claim.
     *
     * JWT structure:
     * - Header: typ="oauth-client-attestation-pop+jwt", alg="ES256"
     * - Payload: iss=client_id, aud=auth_server_issuer, jti=uuid, iat
     * - Signed by: Wallet Key (same key as WIA cnf.jwk)
     *
     * @param audience The authorization server issuer URL
     * @return Serialized PoP JWT string
     */
    fun createPopJwt(audience: String): String {
        Log.d(TAG, "Creating WIA PoP JWT for audience: $audience")

        val header = JWSHeader.Builder(JWSAlgorithm.ES256)
            .type(JOSEObjectType("oauth-client-attestation-pop+jwt"))
            .build()

        val now = Date()
        val claims = JWTClaimsSet.Builder()
            .issuer(AppConfig.CLIENT_ID)
            .audience(audience)
            .jwtID(UUID.randomUUID().toString())
            .issueTime(now)
            .build()

        val signedJWT = SignedJWT(header, claims)
        val signer = AndroidKeystoreSigner(AppConfig.KEY_ALIAS) // Wallet Key
        signedJWT.sign(signer)

        Log.d(TAG, "WIA PoP JWT created successfully")
        return signedJWT.serialize()
    }

    /**
     * Complete WIA issuance flow.
     * This is the main entry point for obtaining a WIA.
     *
     * @return Result containing the WIA credential response
     */
    suspend fun issueWia(): Result<WiaCredentialResponse> {
        Log.d(TAG, "Starting WIA issuance flow")

        return try {
            // Step 1: Get nonce
            val nonceResponse = getNonce()
            val nonce = nonceResponse.cNonce

            // Step 2: Create JWT proof
            val jwtProof = createWiaProof(nonce)

            // Step 3: Request WIA credential
            val result = requestWiaCredential(jwtProof)

            if (result.isSuccess) {
                val wiaResponse = result.getOrNull()!!
                Log.d(TAG, "WIA JWT (first 100 chars): ${wiaResponse.credential.take(100)}")
                Log.d(TAG, "WIA JWT length: ${wiaResponse.credential.length}")

                // Step 4: Verify WIA signature
                val isValid = verifyWiaCredential(wiaResponse.credential)
                if (!isValid) {
                    return Result.failure(Exception("WIA credential signature verification failed"))
                }

                // Step 5: Store WIA
                storeWia(wiaResponse)

                Log.d(TAG, "WIA issuance completed successfully")
            }

            result
        } catch (e: Exception) {
            Log.e(TAG, "WIA issuance failed", e)
            Result.failure(e)
        }
    }

    /**
     * Store WIA credential in encrypted SharedPreferences.
     */
    private fun storeWia(wiaResponse: WiaCredentialResponse) {
        encryptedPrefs.edit()
            .putString(AppConfig.STORED_WIA, wiaResponse.credential)
            .putString(AppConfig.WIA_ID, wiaResponse.wiaId)
            .apply()
        Log.d(TAG, "WIA stored successfully. WIA ID: ${wiaResponse.wiaId}")
    }

    /**
     * Get stored WIA credential from encrypted SharedPreferences.
     *
     * @return The WIA JWT string, or null if not available, expired, or prefs not initialized
     */
    fun getStoredWia(): String? {
        if (_encryptedPrefs == null) {
            Log.d(TAG, "WiaService not initialized with Activity, cannot get stored WIA")
            return null
        }

        val wia = encryptedPrefs.getString(AppConfig.STORED_WIA, null)
        if (wia.isNullOrEmpty()) {
            Log.d(TAG, "No stored WIA found")
            return null
        }

        if (!isWiaValid(wia)) {
            Log.d(TAG, "Stored WIA is expired or invalid")
            return null
        }

        return wia
    }

    /**
     * Check if a WIA credential is valid (not expired).
     *
     * @param wiaJwt The WIA JWT to check
     * @return true if the WIA is valid and not expired
     */
    fun isWiaValid(wiaJwt: String? = null): Boolean {
        val jwt = wiaJwt ?: encryptedPrefs.getString(AppConfig.STORED_WIA, null)
        if (jwt.isNullOrEmpty()) {
            return false
        }

        return try {
            val signedJwt = SignedJWT.parse(jwt)
            val exp = signedJwt.jwtClaimsSet.expirationTime
            if (exp == null) {
                Log.w(TAG, "WIA has no expiration time")
                false
            } else {
                val isValid = exp.after(Date())
                Log.d(TAG, "WIA expiration check: exp=$exp, isValid=$isValid")
                isValid
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking WIA validity", e)
            false
        }
    }

    /**
     * Fetch Wallet Provider's JWK Set for signature verification.
     */
    private suspend fun fetchWalletProviderJWKSet(): JWKSet {
        return try {
            val response: HttpResponse = client.get("${AppConfig.WALLET_PROVIDER_URL}/.well-known/jwks.json")
            val jwksJson: String = response.bodyAsText()
            JWKSet.parse(jwksJson)
        } catch (e: Exception) {
            throw RuntimeException("Failed to fetch Wallet Provider JWK Set: ${e.message}")
        }
    }
}
