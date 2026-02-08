package com.example.eudiwemu.service.sdjwt

import android.util.Base64
import android.util.Log
import com.example.eudiwemu.config.AppConfig
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jwt.SignedJWT
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey

class SdJwtCredentialService(private val client: HttpClient) {

    companion object {
        private const val TAG = "SdJwtCredentialService"
    }

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun verify(sdJwt: String) {
        Log.d(TAG, "Verifying SD-JWT...")
        if (sdJwt.isEmpty()) throw RuntimeException("No SD-JWT included!")

        val parts = sdJwt.split("~")
        val jwtPart = parts.firstOrNull() ?: throw IllegalArgumentException("Invalid SD-JWT format")
        val disclosures = parts.drop(1).filter { it.isNotEmpty() }

        Log.d(TAG, "Parsed JWT: $jwtPart")
        Log.d(TAG, "Number of disclosures: ${disclosures.size}")

        val signedJwt = SignedJWT.parse(jwtPart)

        val jwkSet = fetchIssuerJWKSet("${AppConfig.ISSUER_URL}/.well-known/jwks.json")
        val jwksKey = jwkSet.keys.first().toECKey()

        val x5cKey = extractKeyFromX5c(signedJwt)
        if (!keysMatch(jwksKey, x5cKey)) {
            throw SecurityException("x5c key does not match issuer JWKS - possible tampering")
        }
        Log.d(TAG, "x5c key matches JWKS key - cross-check passed")

        val verifier = ECDSAVerifier(jwksKey)
        val isValid = signedJwt.verify(verifier)
        Log.d(TAG, "Signature valid: $isValid")

        if (!isValid) throw RuntimeException("Invalid SD-JWT signature!")
    }

    fun decode(sdJwt: String): Map<String, Any> {
        Log.d(TAG, "Decoding SD-JWT...")

        val parts = sdJwt.split("~")
        val disclosures = parts.drop(1).filter { it.isNotEmpty() }

        Log.d(TAG, "Number of disclosures: ${disclosures.size}")

        val decodedClaims = mutableMapOf<String, Any>()

        for (disclosure in disclosures) {
            try {
                val decodedBytes = Base64.decode(disclosure, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                val decodedJson = String(decodedBytes)
                val claimData = json.parseToJsonElement(decodedJson).jsonArray

                if (claimData.size >= 3) {
                    val claimName = claimData[1].jsonPrimitive.content
                    val claimValue = claimData[2]

                    if (claimValue is JsonObject && claimValue.containsKey("_sd")) {
                        Log.d(TAG, "Skipping parent container disclosure: $claimName")
                        continue
                    }

                    val parsedValue: Any = if (claimValue is JsonObject) {
                        claimValue.jsonObject.mapValues { entry ->
                            when (val v = entry.value) {
                                is JsonPrimitive -> v.content
                                else -> v.toString()
                            }
                        }
                    } else {
                        claimValue.jsonPrimitive.content
                    }
                    decodedClaims[claimName] = parsedValue
                    Log.d(TAG, "Decoded Claim: $claimName -> $parsedValue")
                } else {
                    Log.d(TAG, "Malformed disclosure: $decodedJson")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error decoding disclosure: $disclosure", e)
            }
        }

        return decodedClaims
    }

    private fun extractKeyFromX5c(signedJwt: SignedJWT): ECKey {
        val x5cChain = signedJwt.header.x509CertChain
            ?: throw IllegalArgumentException("No x5c certificate chain in credential JWT header")

        if (x5cChain.isEmpty()) {
            throw IllegalArgumentException("Empty x5c certificate chain")
        }

        val certBytes = x5cChain[0].decode()
        val certFactory = CertificateFactory.getInstance("X.509")
        val certificate = certFactory.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate

        val ecPublicKey = certificate.publicKey as ECPublicKey
        return ECKey.Builder(Curve.P_256, ecPublicKey).build()
    }

    private fun keysMatch(jwksKey: ECKey, x5cKey: ECKey): Boolean {
        return try {
            val jwksThumbprint = jwksKey.computeThumbprint().toString()
            val x5cThumbprint = x5cKey.computeThumbprint().toString()
            jwksThumbprint == x5cThumbprint
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compute key thumbprints", e)
            false
        }
    }

    private suspend fun fetchIssuerJWKSet(jwksUrl: String): JWKSet {
        return try {
            val response: HttpResponse = client.get(jwksUrl)
            val jwksJson: String = response.body()
            JWKSet.parse(jwksJson)
        } catch (e: Exception) {
            throw RuntimeException("Failed to fetch JWK Set: ${e.message}")
        }
    }
}
