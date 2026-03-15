package dev.kmandalas.wallet.security

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSSigner
import com.nimbusds.jose.jca.JCAContext
import com.nimbusds.jose.util.Base64URL
import dev.kmandalas.wallet.service.QtspService
import kotlinx.coroutines.runBlocking

/**
 * A JWSSigner implementation that delegates signing to a remote QTSP via the CSC API.
 *
 * Delegates the hash→authorize→signHash→DER-to-JOSE pipeline to [QtspService.signRaw].
 *
 * Note: sign() is synchronous (JWSSigner contract) but QTSP calls are async.
 * Uses runBlocking internally — acceptable for a demo, not for production.
 */
class RemoteQtspSigner(
    private val qtspService: QtspService,
    private val credentialId: String
) : JWSSigner {

    override fun getJCAContext(): JCAContext = JCAContext()

    override fun supportedJWSAlgorithms(): MutableSet<JWSAlgorithm> =
        mutableSetOf(JWSAlgorithm.ES256)

    override fun sign(header: JWSHeader?, signingInput: ByteArray?): Base64URL {
        requireNotNull(signingInput) { "Signing input must not be null" }
        return runBlocking {
            Base64URL.encode(qtspService.signRaw(credentialId, signingInput))
        }
    }

}
