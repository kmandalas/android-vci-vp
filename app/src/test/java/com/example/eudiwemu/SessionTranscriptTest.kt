package com.example.eudiwemu

import com.example.eudiwemu.service.mdoc.SessionTranscriptBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Validates our SessionTranscript against the official OID4VP spec test vectors.
 * Reference: https://openid.net/specs/openid-4-verifiable-presentations-1_0.html#name-handover-and-sessiontranscr
 * Same vectors used by eudi-lib-android-wallet-core (Openid4VpUtilsTest.kt).
 */
class SessionTranscriptTest {

    companion object {
        const val CLIENT_ID = "x509_san_dns:example.com"
        const val NONCE = "exc7gBkxjx1rdc9udRrveKvSsJIq80avlXeLHhGwqtA"
        const val RESPONSE_URI = "https://example.com/response"
        const val JWK_JSON = """
            {
              "kty": "EC",
              "crv": "P-256",
              "x": "DxiH5Q4Yx3UrukE2lWCErq8N8bqC9CHLLrAwLz5BmE0",
              "y": "XtLM4-3h5o3HUH0MHVJV0kyq0iBlrBwlh8qEDMZ4-Pc",
              "use": "enc",
              "alg": "ECDH-ES",
              "kid": "1"
            }
        """

        // Expected outputs from the OID4VP spec
        const val EXPECTED_SESSION_TRANSCRIPT_HEX =
            "83f6f682714f70656e494434565048616e646f7665725820048bc053c00442af9b8eed494cefdd9d95240d254b046b11b68013722aad38ac"
    }

    @Test
    fun sessionTranscriptMatchesSpecVector() {
        val ephemeralJwk = Json.parseToJsonElement(JWK_JSON).jsonObject

        val sessionTranscriptBytes = SessionTranscriptBuilder.build(
            clientId = CLIENT_ID,
            nonce = NONCE,
            ephemeralJwk = ephemeralJwk,
            responseUri = RESPONSE_URI
        )

        val actualHex = sessionTranscriptBytes.joinToString("") { "%02x".format(it) }

        assertEquals(
            "SessionTranscript does not match OID4VP spec test vector",
            EXPECTED_SESSION_TRANSCRIPT_HEX,
            actualHex
        )
    }

    @Test
    fun sessionTranscriptWithNullEphemeralKey() {
        // Verify it doesn't crash with null ephemeral key (direct_post mode)
        val sessionTranscriptBytes = SessionTranscriptBuilder.build(
            clientId = CLIENT_ID,
            nonce = NONCE,
            ephemeralJwk = null,
            responseUri = RESPONSE_URI
        )

        // Should produce valid CBOR starting with 83 f6 f6 (3-element array, null, null)
        val hex = sessionTranscriptBytes.joinToString("") { "%02x".format(it) }
        assert(hex.startsWith("83f6f6")) { "Expected SessionTranscript to start with [null, null, ...], got: $hex" }
    }
}
