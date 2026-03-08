package dev.kmandalas.wallet.ui.viewmodel

import com.authlete.sd.Disclosure
import dev.kmandalas.wallet.dto.AuthorizationRequestResponse
import dev.kmandalas.wallet.config.AppConfig
import dev.kmandalas.wallet.dto.CredentialConfiguration
import dev.kmandalas.wallet.security.SecurityPostureLevel
import dev.kmandalas.wallet.service.mdoc.DeviceRequestParser
import dev.kmandalas.wallet.util.ClaimMetadataResolver

data class CredentialUiState(
    val credentialKey: String = "",
    val credentialFormat: String = "",
    val vct: String? = null,
    val claims: Map<String, Any>? = null,
    val claimResolver: ClaimMetadataResolver? = null,
    val credentialDisplayName: String? = null,
    val issuedAt: Long? = null,
    val expiresAt: Long? = null
)

data class AttestationState(
    val wuaInfo: Map<String, Any>? = null,
    val wiaInfo: Map<String, Any>? = null
)

data class IssuanceUiState(
    val credentialTypes: Map<String, String> = mapOf("Portable Document A1 (PDA1)" to "eu.europa.ec.eudi.pda1_sd_jwt_vc"),
    val credentialConfigs: Map<String, CredentialConfiguration> = emptyMap(),
    val selectedLabel: String = "",
    val selectedValue: String = "",
    val isLoading: Boolean = false,
    val conformanceIssuerUrl: String = AppConfig.CONFORMANCE_ISSUER_URL
) {
    val isConformanceTest: Boolean get() = selectedValue == AppConfig.CONFORMANCE_TEST_VALUE
}

data class VpRequestState(
    val clientId: String = "",
    val requestUri: String = "",
    val responseUri: String = "",
    val nonce: String = "",
    val clientName: String = "",
    val logoUri: String = "",
    val purpose: String = "",
    val authRequest: AuthorizationRequestResponse? = null,
    val selectedClaims: List<Disclosure>? = null,
    val mdocAvailableClaims: List<String>? = null,
    val vpClaimResolver: ClaimMetadataResolver? = null,
    val targetCredentialKey: String? = null
)

data class PostureState(
    val level: SecurityPostureLevel? = null,
    val findings: List<String> = emptyList(),
    val lastChecked: Long? = null
)

data class ProximityState(
    val isActive: Boolean = false,
    val qrContent: String? = null,
    val status: String = "",
    val requestedClaims: List<String>? = null,
    val parsedRequest: DeviceRequestParser.ParsedRequest? = null
)

sealed class WalletEvent {
    data class ShowSnackbar(val message: String) : WalletEvent()
    data class OpenBrowser(val uri: String) : WalletEvent()
    data class NavigateToDetail(val credentialKey: String) : WalletEvent()
    data object NavigateToTransactionLog : WalletEvent()
    data class ExportReady(val data: ByteArray) : WalletEvent()
    data class ImportComplete(
        val logCount: Int,
        val credentialHints: List<dev.kmandalas.wallet.service.ExportImportService.CredentialMetadataDto>
    ) : WalletEvent()
}
