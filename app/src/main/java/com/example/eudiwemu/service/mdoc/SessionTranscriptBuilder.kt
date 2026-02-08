package com.example.eudiwemu.service.mdoc

import android.util.Log
import com.authlete.cbor.CBORByteArray
import com.authlete.cbor.CBORItemList
import com.authlete.cbor.CBORNull
import com.authlete.cbor.CBORString
import com.nimbusds.jose.jwk.JWK
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

/**
 * Builds the OID4VP SessionTranscript matching the verifier's MDocVerifierService.
 *
 * SessionTranscript = [null, null, Handover]
 * Handover = ["OpenID4VPHandover", SHA256(CBOR.encode(OID4VPHandoverInfo))]
 * OID4VPHandoverInfo = [clientId, nonce, ephemeralKeyThumbprint, responseUri]
 */
object SessionTranscriptBuilder {

    private const val TAG = "SessionTranscriptBuilder"

    /**
     * Build CBOR-encoded SessionTranscript bytes.
     *
     * @param clientId    The client_id from the authorization request (e.g., "x509_hash:SHA256:...")
     * @param nonce       The nonce from the authorization request
     * @param ephemeralJwk The ephemeral encryption key from client_metadata.jwks.keys[0] (may be null)
     * @param responseUri The response_uri where the VP will be posted
     * @return CBOR-encoded SessionTranscript bytes
     */
    fun build(
        clientId: String,
        nonce: String,
        ephemeralJwk: JsonObject?,
        responseUri: String
    ): ByteArray {
        val sha256 = MessageDigest.getInstance("SHA-256")

        // Step 1: Compute ephemeral key thumbprint (JWK thumbprint SHA-256 -> raw bytes)
        val ephemeralKeyThumbprintItem = if (ephemeralJwk != null) {
            val thumbprintBytes = computeJwkThumbprint(ephemeralJwk)
            CBORByteArray(thumbprintBytes)
        } else {
            CBORNull.INSTANCE
        }

        // Step 2: Build OID4VPHandoverInfo = [clientId, nonce, ephemeralKeyThumbprint, responseUri]
        val oid4vpHandoverInfo = CBORItemList(
            CBORString(clientId),
            CBORString(nonce),
            ephemeralKeyThumbprintItem,
            CBORString(responseUri)
        )

        // Step 3: CBOR-encode the handover info, then SHA-256 hash it
        val handoverInfoBytes = oid4vpHandoverInfo.encode()
        val handoverInfoHash = sha256.digest(handoverInfoBytes)

        Log.d(TAG, "OID4VPHandoverInfo CBOR hex: ${handoverInfoBytes.toHexString()}")
        Log.d(TAG, "OID4VPHandoverInfo hash hex: ${handoverInfoHash.toHexString()}")

        // Step 4: Build Handover = ["OpenID4VPHandover", handoverInfoHash]
        val handover = CBORItemList(
            CBORString("OpenID4VPHandover"),
            CBORByteArray(handoverInfoHash)
        )

        // Step 5: Build SessionTranscript = [null, null, Handover]
        val sessionTranscript = CBORItemList(
            CBORNull.INSTANCE,
            CBORNull.INSTANCE,
            handover
        )

        return sessionTranscript.encode()
    }

    /**
     * Compute JWK thumbprint (RFC 7638) as raw SHA-256 bytes using Nimbus.
     */
    private fun computeJwkThumbprint(jwk: JsonObject): ByteArray {
        val nimbusJwk = JWK.parse(jwk.toString())
        return nimbusJwk.computeThumbprint("SHA-256").decode()
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it) }
}
