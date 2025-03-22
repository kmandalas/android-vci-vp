package com.example.eudiwemu.security

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSSigner
import com.nimbusds.jose.crypto.impl.ECDSA
import com.nimbusds.jose.jca.JCAContext
import com.nimbusds.jose.util.Base64URL
import java.security.KeyStore
import java.security.Signature

class AndroidKeystoreSigner(private val privateKeyAlias: String) : JWSSigner {

    override fun getJCAContext(): JCAContext {
        return JCAContext()
    }

    override fun supportedJWSAlgorithms(): MutableSet<JWSAlgorithm> {
        return mutableSetOf(JWSAlgorithm.ES256) // Supports ES256 (P-256 curve)
    }

    override fun sign(header: JWSHeader?, signingInput: ByteArray?): Base64URL {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val entry = keyStore.getEntry(privateKeyAlias, null) as KeyStore.PrivateKeyEntry

        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(entry.privateKey)
            update(signingInput)
        }
        val derSignature = signature.sign()

        // Convert DER-encoded signature to JOSE format
        val joseSignature = ECDSA.transcodeSignatureToConcat(derSignature, 64)

        return Base64URL.encode(joseSignature)
    }

}

