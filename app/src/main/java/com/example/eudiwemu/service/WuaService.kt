package com.example.eudiwemu.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.example.eudiwemu.config.AppConfig
import com.example.eudiwemu.dto.WuaCredentialRequest
import com.example.eudiwemu.dto.WuaCredentialResponse
import com.example.eudiwemu.dto.WuaKeyAttestation
import com.example.eudiwemu.dto.WuaNonceResponse
import com.example.eudiwemu.dto.WuaProof
import com.example.eudiwemu.dto.WuaStatusResponse
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

/**
 * Service responsible for Wallet Unit Attestation (WUA) issuance.
 *
 * WUA is obtained from the Wallet Provider during wallet activation and proves:
 * 1. The wallet unit is authentic and trusted
 * 2. The wallet's cryptographic keys are hardware-backed (TEE/StrongBox)
 * 3. The wallet can be revoked if compromised
 *
 * Flow:
 * 1. Get nonce from Wallet Provider
 * 2. Generate WUA key with attestation challenge (nonce as challenge)
 * 3. Create JWT proof signed with WUA key
 * 4. Submit proof + key attestation certificate chain
 * 5. Receive and verify WUA credential
 */
class WuaService(
    private val client: HttpClient,
    private val walletKeyManager: WalletKeyManager,
    private val context: Context
) {
    companion object {
        private const val TAG = "WuaIssuanceService"
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
            "WuaService not initialized with Activity. Call initWithActivity() first."
        )

    /**
     * Get a fresh nonce from the Wallet Provider.
     * This nonce is used both in the JWT proof and as the attestation challenge.
     */
    suspend fun getNonce(): WuaNonceResponse {
        Log.d(TAG, "Requesting nonce from Wallet Provider")
        val response: WuaNonceResponse = client.get("${AppConfig.WALLET_PROVIDER_URL}/wua/nonce").body()
        Log.d(TAG, "Received nonce: ${response.cNonce}")
        return response
    }

    /**
     * Create JWT proof for WUA credential request.
     * The proof demonstrates possession of the WUA private key.
     *
     * @param nonce The nonce from the Wallet Provider
     * @return Serialized JWT proof string
     */
    fun createWuaProof(nonce: String): String {
        Log.d(TAG, "Creating WUA JWT proof")

        val ecKey = walletKeyManager.getWuaKey()
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
        val signer = AndroidKeystoreSigner(AppConfig.WUA_KEY_ALIAS)
        signedJWT.sign(signer)

        Log.d(TAG, "WUA JWT proof created successfully")
        return signedJWT.serialize()
    }

    /**
     * Request WUA credential from the Wallet Provider.
     *
     * @param jwtProof The signed JWT proof
     * @param certificateChain Base64-encoded X.509 certificate chain from Android Key Attestation
     * @return WuaCredentialResponse containing the WUA JWT
     */
    suspend fun requestWuaCredential(
        jwtProof: String,
        certificateChain: List<String>
    ): Result<WuaCredentialResponse> {
        Log.d(TAG, "Requesting WUA credential with ${certificateChain.size} certificates in chain")

        val request = WuaCredentialRequest(
            proof = WuaProof(jwt = jwtProof),
            keyAttestation = WuaKeyAttestation(certificateChain = certificateChain)
        )

        return try {
            val response: HttpResponse = client.post("${AppConfig.WALLET_PROVIDER_URL}/wua/credential") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }

            if (response.status.isSuccess()) {
                val rawBody = response.bodyAsText()
                Log.d(TAG, "WUA raw response: $rawBody")
                val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                val wuaResponse = json.decodeFromString(WuaCredentialResponse.serializer(), rawBody)
                Log.d(TAG, "WUA credential received. WUA ID: ${wuaResponse.wuaId}")
                Result.success(wuaResponse)
            } else {
                val errorBody = response.bodyAsText()
                Log.e(TAG, "WUA credential request failed: $errorBody")
                Result.failure(Exception("WUA issuance failed: $errorBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting WUA credential", e)
            Result.failure(e)
        }
    }

    /**
     * Verify the WUA credential signature using the Wallet Provider's JWKS.
     *
     * @param wuaJwt The WUA credential JWT to verify
     * @return true if signature is valid
     */
    suspend fun verifyWuaCredential(wuaJwt: String): Boolean {
        Log.d(TAG, "Verifying WUA credential signature")

        return try {
            val signedJwt = SignedJWT.parse(wuaJwt)
            val jwkSet = fetchWalletProviderJWKSet()
            val publicKey = jwkSet.keys.first().toECKey()
            val verifier = ECDSAVerifier(publicKey)
            val isValid = signedJwt.verify(verifier)
            Log.d(TAG, "WUA signature valid: $isValid")
            isValid
        } catch (e: Exception) {
            Log.e(TAG, "Error verifying WUA credential", e)
            false
        }
    }

    /**
     * Get WUA status from the Wallet Provider.
     *
     * @param wuaId The WUA identifier
     * @return WuaStatusResponse with current status
     */
    suspend fun getWuaStatus(wuaId: String): Result<WuaStatusResponse> {
        Log.d(TAG, "Getting WUA status for ID: $wuaId")

        return try {
            val response: WuaStatusResponse = client.get("${AppConfig.WALLET_PROVIDER_URL}/wua/status/$wuaId").body()
            Log.d(TAG, "WUA status: ${response.status}")
            Result.success(response)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting WUA status", e)
            Result.failure(e)
        }
    }

    /**
     * Decode WUA credential claims for display.
     * Supports both TS3 format (wscd_info) and legacy format (key_storage_info).
     *
     * @param wuaJwt The WUA credential JWT
     * @return Map of claim names to values
     */
    @Suppress("UNCHECKED_CAST")
    fun decodeWuaCredential(wuaJwt: String): Map<String, Any> {
        Log.d(TAG, "Decoding WUA credential")

        val signedJwt = SignedJWT.parse(wuaJwt)
        val claims = signedJwt.jwtClaimsSet

        val result = mutableMapOf<String, Any>()
        result["issuer"] = claims.issuer ?: "unknown"
        // Use jti (JWT ID) as unique WUA identifier; fallback to legacy 'wua_id'
        result["wua_id"] = claims.jwtid ?: claims.getStringClaim("wua_id") ?: "unknown"
        result["issued_at"] = claims.issueTime?.toString() ?: "unknown"
        result["expires_at"] = claims.expirationTime?.toString() ?: "unknown"
        // Store raw expiration for display formatting
        claims.expirationTime?.let { result["expires_at_date"] = it }

        // Try to extract key_storage from top-level claim (OID4VCI standard)
        val keyStorage = claims.getStringListClaim("key_storage")
        if (!keyStorage.isNullOrEmpty()) {
            result["key_storage"] = keyStorage.first()
            // Map ISO 18045 level to display-friendly WSCD type
            result["wscd_type"] = mapIso18045ToWscdType(keyStorage.first())
            result["security_level"] = keyStorage.first()
        }

        // Extract wallet info from eudi_wallet_info
        val walletInfo = claims.getJSONObjectClaim("eudi_wallet_info")
        if (walletInfo != null) {
            // Try TS3 format first: wscd_info.wscd_certification_information
            val wscdInfo = walletInfo["wscd_info"] as? Map<String, Any>
            if (wscdInfo != null) {
                val wscdCertInfo = wscdInfo["wscd_certification_information"] as? Map<String, Any>
                if (wscdCertInfo != null) {
                    result["wscd_type"] = wscdCertInfo["wscd_type"] ?: result["wscd_type"] ?: "unknown"
                    result["security_level"] = wscdCertInfo["security_level"] ?: result["security_level"] ?: "unknown"
                }
            } else {
                // Fallback to legacy format: key_storage_info.storage_certification_information
                val keyStorageInfo = walletInfo["key_storage_info"] as? Map<String, Any>
                if (keyStorageInfo != null) {
                    val certInfo = keyStorageInfo["storage_certification_information"] as? Map<String, Any>
                    if (certInfo != null) {
                        result["wscd_type"] = certInfo["wscd_type"] ?: "unknown"
                        result["security_level"] = certInfo["security_level"] ?: "unknown"
                    }
                }
            }
        }

        Log.d(TAG, "Decoded WUA claims: $result")
        return result
    }

    /**
     * Map ISO 18045 attack potential resistance level to WSCD type for display.
     */
    private fun mapIso18045ToWscdType(iso18045Level: String): String {
        return when (iso18045Level) {
            "iso_18045_high" -> "strongbox"
            "iso_18045_moderate" -> "tee"
            "iso_18045_enhanced-basic" -> "tee"
            "iso_18045_basic" -> "software"
            else -> "unknown"
        }
    }

    /**
     * Complete WUA issuance flow.
     * This is the main entry point for obtaining a WUA.
     *
     * @return Result containing the WUA credential response
     */
    suspend fun issueWua(): Result<WuaCredentialResponse> {
        Log.d(TAG, "Starting WUA issuance flow")

        return try {
            // Step 1: Get nonce
            val nonceResponse = getNonce()
            val nonce = nonceResponse.cNonce

            // Step 2: Generate WUA key with attestation challenge
            // Use nonce bytes as the attestation challenge
            walletKeyManager.generateWuaKeyWithAttestation(nonce.toByteArray(Charsets.UTF_8))

            // Step 3: Create JWT proof
            val jwtProof = createWuaProof(nonce)

            // Step 4: Get attestation certificate chain
            val certChain = walletKeyManager.getWuaAttestationCertificateChain()

            // Step 5: Request WUA credential
            val result = requestWuaCredential(jwtProof, certChain)

            if (result.isSuccess) {
                val wuaResponse = result.getOrNull()!!
                Log.d(TAG, "WUA JWT (first 100 chars): ${wuaResponse.credential.take(100)}")
                Log.d(TAG, "WUA JWT length: ${wuaResponse.credential.length}")

                // Step 6: Verify WUA signature
                val isValid = verifyWuaCredential(wuaResponse.credential)
                if (!isValid) {
                    return Result.failure(Exception("WUA credential signature verification failed"))
                }

                // Step 7: Store WUA
                storeWua(wuaResponse)

                Log.d(TAG, "WUA issuance completed successfully")
            }

            result
        } catch (e: Exception) {
            Log.e(TAG, "WUA issuance failed", e)
            Result.failure(e)
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

    /**
     * Store WUA credential in encrypted SharedPreferences.
     */
    private fun storeWua(wuaResponse: WuaCredentialResponse) {
        encryptedPrefs.edit()
            .putString(AppConfig.STORED_WUA, wuaResponse.credential)
            .putString(AppConfig.WUA_ID, wuaResponse.wuaId)
            .apply()
        Log.d(TAG, "WUA stored successfully. WUA ID: ${wuaResponse.wuaId}")
    }

    /**
     * Get stored WUA credential from encrypted SharedPreferences.
     *
     * @return The WUA JWT string, or null if not available, expired, or prefs not initialized
     */
    fun getStoredWua(): String? {
        if (_encryptedPrefs == null) {
            Log.d(TAG, "WuaService not initialized with Activity, cannot get stored WUA")
            return null
        }

        val wua = encryptedPrefs.getString(AppConfig.STORED_WUA, null)
        if (wua.isNullOrEmpty()) {
            Log.d(TAG, "No stored WUA found")
            return null
        }

        if (!isWuaValid(wua)) {
            Log.d(TAG, "Stored WUA is expired or invalid")
            return null
        }

        return wua
    }

    /**
     * Check if a WUA credential is valid (not expired).
     *
     * @param wuaJwt The WUA JWT to check
     * @return true if the WUA is valid and not expired
     */
    fun isWuaValid(wuaJwt: String? = null): Boolean {
        val jwt = wuaJwt ?: _encryptedPrefs?.getString(AppConfig.STORED_WUA, null)
        if (jwt.isNullOrEmpty()) {
            return false
        }

        return try {
            val signedJwt = SignedJWT.parse(jwt)
            val exp = signedJwt.jwtClaimsSet.expirationTime
            if (exp == null) {
                Log.w(TAG, "WUA has no expiration time")
                false
            } else {
                val isValid = exp.after(Date())
                Log.d(TAG, "WUA expiration check: exp=$exp, isValid=$isValid")
                isValid
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking WUA validity", e)
            false
        }
    }
}
