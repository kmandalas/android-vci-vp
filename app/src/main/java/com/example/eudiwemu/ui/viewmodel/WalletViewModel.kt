package com.example.eudiwemu.ui.viewmodel

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.authlete.sd.Disclosure
import com.example.eudiwemu.config.AppConfig
import com.example.eudiwemu.security.PkceManager
import com.example.eudiwemu.security.getEncryptedPrefs
import com.example.eudiwemu.service.IssuanceService
import com.example.eudiwemu.service.VpTokenService
import com.example.eudiwemu.service.WiaService
import com.example.eudiwemu.service.WuaService
import com.example.eudiwemu.service.mdoc.DeviceResponseBuilder
import com.example.eudiwemu.service.mdoc.ProximityPresentationService
import com.example.eudiwemu.util.ClaimMetadataResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class WalletViewModel(
    private val issuanceService: IssuanceService,
    private val vpTokenService: VpTokenService,
    private val wuaService: WuaService,
    private val wiaService: WiaService
) : ViewModel() {

    var credentialList by mutableStateOf<List<CredentialUiState>>(emptyList())
        private set

    var selectedCredentialKey by mutableStateOf<String?>(null)
        private set

    var attestationState by mutableStateOf(AttestationState())
        private set

    var issuanceState by mutableStateOf(IssuanceUiState())
        private set

    var vpRequestState by mutableStateOf(VpRequestState())
        private set

    var proximityState by mutableStateOf(ProximityState())
        private set

    private var proximityService: ProximityPresentationService? = null

    private val _events = Channel<WalletEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val selectedFormatValue: String
        get() = issuanceState.credentialConfigs[issuanceState.selectedValue]?.format
            ?: AppConfig.FORMAT_SD_JWT

    fun updateSelectedCredentialType(label: String, value: String) {
        issuanceState = issuanceState.copy(selectedLabel = label, selectedValue = value)
    }

    fun dismissSdJwtDialog() {
        vpRequestState = vpRequestState.copy(selectedClaims = null)
    }

    fun dismissMDocDialog() {
        vpRequestState = vpRequestState.copy(mdocAvailableClaims = null)
    }

    fun selectCredential(credentialKey: String) {
        selectedCredentialKey = credentialKey
    }

    fun clearSelection() {
        selectedCredentialKey = null
    }

    fun initialize(activity: FragmentActivity) {
        viewModelScope.launch {
            try {
                wuaService.initWithActivity(activity)
                wiaService.initWithActivity(activity)
                issuanceService.initWithActivity(activity)

                // Fetch issuer metadata for dynamic dropdown (skip if already cached)
                if (issuanceState.credentialConfigs.isEmpty()) {
                    try {
                        val metadata = withContext(Dispatchers.IO) {
                            issuanceService.fetchIssuerMetadata()
                        }
                        val configs = metadata.credential_configurations_supported
                        val dynamicTypes = configs.mapNotNull { (configId, config) ->
                            val displayName = config.resolvedDisplay()?.firstOrNull()?.name
                            if (displayName != null) displayName to configId else null
                        }.toMap()
                        issuanceState = issuanceState.copy(
                            credentialConfigs = configs,
                            credentialTypes = dynamicTypes.ifEmpty { issuanceState.credentialTypes }
                        )
                        Log.d("WalletApp", "Loaded ${configs.size} credential configurations from issuer metadata")
                    } catch (e: Exception) {
                        Log.w("WalletApp", "Failed to fetch issuer metadata, using fallback dropdown", e)
                    }
                }

                // Load WUA
                val storedWua = wuaService.getStoredWua()
                if (!storedWua.isNullOrEmpty()) {
                    try {
                        attestationState = attestationState.copy(wuaInfo = wuaService.decodeWuaCredential(storedWua))
                    } catch (e: Exception) {
                        Log.e("WalletApp", "Error decoding stored WUA", e)
                    }
                }

                // Load WIA
                val storedWia = wiaService.getStoredWia()
                if (!storedWia.isNullOrEmpty()) {
                    try {
                        attestationState = attestationState.copy(wiaInfo = wiaService.decodeWiaCredential(storedWia))
                    } catch (e: Exception) {
                        Log.e("WalletApp", "Error decoding stored WIA", e)
                    }
                }

                // Load all stored credentials
                loadAllCredentials()
            } catch (e: Exception) {
                Log.e("WalletApp", "Error initializing services: $e")
            }
        }
    }

    private fun loadAllCredentials() {
        val keys = issuanceService.getAllStoredCredentialKeys()
        val list = keys.mapNotNull { credentialKey ->
            try {
                val bundle = issuanceService.getStoredCredentialBundle(credentialKey) ?: return@mapNotNull null
                val claims = issuanceService.decodeCredential(bundle.rawCredential, bundle.format)
                CredentialUiState(
                    credentialKey = credentialKey,
                    credentialFormat = bundle.format,
                    claims = claims,
                    claimResolver = ClaimMetadataResolver.fromNullable(bundle.claimsMetadata),
                    credentialDisplayName = bundle.displayMetadata?.firstOrNull()?.name,
                    issuedAt = bundle.issuedAt,
                    expiresAt = bundle.expiresAt
                )
            } catch (e: Exception) {
                Log.e("WalletApp", "Error decoding stored credential: $credentialKey", e)
                null
            }
        }
        credentialList = list
    }

    fun handleDeepLink(intent: Intent?, activity: FragmentActivity) {
        val uri = intent?.data ?: return
        viewModelScope.launch {
            when (uri.scheme) {
                "myapp" -> handleOAuthDeepLink(uri, activity)
                "openid4vp", "haip-vp" -> handleVpTokenDeepLink(uri)
                else -> Log.w("WalletApp", "Unknown deep link scheme: ${uri.scheme}")
            }
        }
    }

    private suspend fun handleOAuthDeepLink(uri: Uri, activity: FragmentActivity) {
        val code = uri.getQueryParameter("code") ?: return
        Log.d("OAuth", "Authorization code: $code")

        val context = activity.applicationContext
        val codeVerifier = PkceManager.getCodeVerifier(context)
        val tokenResult = withContext(Dispatchers.IO) {
            issuanceService.exchangeAuthorizationCodeForToken(code, codeVerifier)
        }

        if (tokenResult.isSuccess) {
            val accessToken = tokenResult.getOrNull()
            val prefs = getEncryptedPrefs(context, activity)
            prefs.edit { putString("access_token", accessToken) }

            // Recover the format saved before the OAuth redirect (ViewModel is recreated)
            val pendingFormat = prefs.getString("pending_credential_format", null) ?: selectedFormatValue
            prefs.edit { remove("pending_credential_format") }
            Log.d("OAuth", "Access token saved! Using format: $pendingFormat")

            issuanceState = issuanceState.copy(isLoading = true)

            try {
                val configId = if (pendingFormat == AppConfig.FORMAT_MSO_MDOC)
                    AppConfig.MDOC_CREDENTIAL_CONFIG_ID else AppConfig.SD_JWT_CREDENTIAL_CONFIG_ID
                val credentialConfig = issuanceState.credentialConfigs[configId]
                val vcResult = submitCredentialRequest(activity, credentialConfig, pendingFormat)

                issuanceState = issuanceState.copy(isLoading = false)
                if (vcResult.isSuccess) {
                    val (message, credentialKey) = vcResult.getOrNull()!!
                    addOrReplaceCredentialInList(credentialKey)
                    _events.send(WalletEvent.ShowSnackbar(message))
                    _events.send(WalletEvent.NavigateToDetail(credentialKey))
                } else {
                    _events.send(WalletEvent.ShowSnackbar("❌ Error: ${vcResult.exceptionOrNull()?.message}"))
                }
            } catch (e: Exception) {
                Log.e("WalletApp", "Error requesting VC after OAuth", e)
                issuanceState = issuanceState.copy(isLoading = false)
                _events.send(WalletEvent.ShowSnackbar("❌ Error requesting VC: ${e.message}"))
            }
        } else {
            Log.e("OAuth", "Failed to exchange code: ${tokenResult.exceptionOrNull()?.message}")
        }
    }

    private suspend fun handleVpTokenDeepLink(uri: Uri) {
        val extractedClientId = uri.getQueryParameter("client_id")
        val extractedRequestUri = uri.getQueryParameter("request_uri")

        vpRequestState = vpRequestState.copy(
            clientId = extractedClientId ?: vpRequestState.clientId,
            requestUri = extractedRequestUri ?: vpRequestState.requestUri
        )

        try {
            val requestObject = withContext(Dispatchers.IO) {
                vpTokenService.getRequestObject(
                    extractedRequestUri.orEmpty(),
                    extractedClientId.orEmpty()
                )
            }

            vpRequestState = vpRequestState.copy(
                responseUri = requestObject.response_uri,
                clientName = requestObject.client_metadata?.client_name ?: "",
                logoUri = requestObject.client_metadata?.logo_uri ?: "",
                purpose = requestObject.client_metadata?.purpose ?: "Credential verification request",
                clientId = requestObject.client_id ?: extractedClientId ?: "",
                nonce = requestObject.nonce,
                authRequest = requestObject
            )

            val dcqlFormat = vpTokenService.extractFormatFromDcql(requestObject)
            Log.d("WalletApp", "VP DCQL requested format: $dcqlFormat")

            // Find matching credential by format
            val matchedCredential = credentialList.find { it.credentialFormat == dcqlFormat }
            if (matchedCredential == null) {
                Log.w("WalletApp", "No stored credential matches DCQL format: $dcqlFormat")
                _events.send(WalletEvent.ShowSnackbar("⚠️ No matching credential for requested format"))
                return
            }

            val targetKey = matchedCredential.credentialKey
            vpRequestState = vpRequestState.copy(targetCredentialKey = targetKey)

            val bundle = withContext(Dispatchers.IO) {
                issuanceService.getStoredCredentialBundle(targetKey)
            } ?: return

            if (dcqlFormat == AppConfig.FORMAT_MSO_MDOC) {
                val requestedClaims = requestObject.dcql_query.credentials.firstOrNull()
                    ?.claims?.mapNotNull { it.path.lastOrNull() } ?: emptyList()
                Log.d("WalletApp", "mDoc requested claims: $requestedClaims")
                vpRequestState = vpRequestState.copy(mdocAvailableClaims = requestedClaims)
            } else {
                val result = withContext(Dispatchers.IO) {
                    vpTokenService.extractRequestedClaims(requestObject, bundle.rawCredential, bundle.claimsMetadata)
                }
                vpRequestState = vpRequestState.copy(
                    selectedClaims = result.disclosures,
                    vpClaimResolver = result.resolver
                )
            }
        } catch (e: Exception) {
            Log.e("WalletApp", "Error handling VP Token flow", e)
        }
    }

    fun requestCredential(activity: FragmentActivity) {
        issuanceState = issuanceState.copy(isLoading = true)
        viewModelScope.launch {
            try {
                val context = activity.applicationContext
                val prefs = getEncryptedPrefs(context, activity)
                val accessToken = prefs.getString("access_token", null)

                if (accessToken.isNullOrEmpty()) {
                    // Persist format so the OAuth callback (new ViewModel) can read it
                    prefs.edit { putString("pending_credential_format", selectedFormatValue) }
                    startAuthorizationFlowWithPar(activity) { error ->
                        Log.e("WalletApp", "PAR failed: $error")
                        startAuthorizationFlowLegacy(activity)
                    }
                    issuanceState = issuanceState.copy(isLoading = false)
                } else {
                    val credentialConfig = issuanceState.credentialConfigs[issuanceState.selectedValue]
                    val result = submitCredentialRequest(activity, credentialConfig, selectedFormatValue)

                    issuanceState = issuanceState.copy(isLoading = false)
                    if (result.isSuccess) {
                        val (message, credentialKey) = result.getOrNull()!!
                        addOrReplaceCredentialInList(credentialKey)
                        _events.send(WalletEvent.ShowSnackbar(message))
                        _events.send(WalletEvent.NavigateToDetail(credentialKey))
                    } else {
                        val errorMsg = result.exceptionOrNull()?.message ?: ""
                        if (isAuthError(errorMsg)) {
                            Log.w("WalletApp", "Token expired or invalid, clearing and restarting auth")
                            prefs.edit {
                                remove("access_token")
                                putString("pending_credential_format", selectedFormatValue)
                            }
                            _events.send(WalletEvent.ShowSnackbar("🔑 Session expired, please authenticate again"))
                            startAuthorizationFlowWithPar(activity) { error ->
                                Log.e("WalletApp", "PAR failed during re-auth: $error")
                                startAuthorizationFlowLegacy(activity)
                            }
                        } else {
                            _events.send(WalletEvent.ShowSnackbar("❌ Error: $errorMsg"))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("WalletApp", "Error requesting VC", e)
                issuanceState = issuanceState.copy(isLoading = false)
                val errorMsg = e.message ?: ""
                val context = activity.applicationContext
                val prefs = getEncryptedPrefs(context, activity)
                if (isAuthError(errorMsg)) {
                    prefs.edit {
                        remove("access_token")
                        putString("pending_credential_format", selectedFormatValue)
                    }
                    _events.send(WalletEvent.ShowSnackbar("🔑 Session expired, please authenticate again"))
                    startAuthorizationFlowWithPar(activity) { error ->
                        Log.e("WalletApp", "PAR failed during error recovery: $error")
                        startAuthorizationFlowLegacy(activity)
                    }
                } else {
                    _events.send(WalletEvent.ShowSnackbar("❌ Error requesting VC: ${e.message}"))
                }
            }
        }
    }

    fun deleteCredential(credentialKey: String) {
        viewModelScope.launch {
            try {
                issuanceService.removeCredential(credentialKey)
                credentialList = credentialList.filter { it.credentialKey != credentialKey }
                if (selectedCredentialKey == credentialKey) {
                    selectedCredentialKey = null
                }
                _events.send(WalletEvent.ShowSnackbar("🗑️ Credential deleted"))
            } catch (e: Exception) {
                Log.e("WalletApp", "Error deleting credential", e)
                _events.send(WalletEvent.ShowSnackbar("❌ Error deleting credential"))
            }
        }
    }

    fun submitSdJwtVpToken(selected: List<Disclosure>) {
        dismissSdJwtDialog()
        viewModelScope.launch {
            try {
                val targetKey = vpRequestState.targetCredentialKey
                    ?: throw IllegalStateException("No target credential for VP")
                val bundle = withContext(Dispatchers.IO) {
                    issuanceService.getStoredCredentialBundle(targetKey)
                } ?: throw IllegalStateException("Credential not found: $targetKey")
                val storedCredential = bundle.rawCredential
                val vpToken = withContext(Dispatchers.IO) {
                    vpTokenService.createSdJwtVpToken(
                        storedCredential, selected,
                        vpRequestState.clientId, vpRequestState.nonce
                    )
                }

                val serverResponse = withContext(Dispatchers.IO) {
                    val authRequest = vpRequestState.authRequest
                        ?: throw IllegalStateException("Authorization request is required")
                    vpTokenService.sendVpTokenToVerifier(vpToken, vpRequestState.responseUri, authRequest)
                }

                handleVerifierResponse(serverResponse)
            } catch (e: Exception) {
                Log.e("WalletApp", "Error sending VP Token", e)
                _events.send(WalletEvent.ShowSnackbar("❌ Error: ${e.message}"))
            }
        }
    }

    fun submitMDocVpToken(selectedNames: List<String>) {
        dismissMDocDialog()
        viewModelScope.launch {
            try {
                val targetKey = vpRequestState.targetCredentialKey
                    ?: throw IllegalStateException("No target credential for VP")
                val bundle = withContext(Dispatchers.IO) {
                    issuanceService.getStoredCredentialBundle(targetKey)
                } ?: throw IllegalStateException("Credential not found: $targetKey")
                val storedCredential = bundle.rawCredential

                val ephemeralJwk = vpRequestState.authRequest?.client_metadata?.jwks?.keys?.firstOrNull()

                val vpToken = withContext(Dispatchers.IO) {
                    vpTokenService.createMDocVpToken(
                        credential = storedCredential,
                        selectedClaims = selectedNames,
                        clientId = vpRequestState.clientId,
                        nonce = vpRequestState.nonce,
                        responseUri = vpRequestState.responseUri,
                        ephemeralJwk = ephemeralJwk
                    )
                }

                val serverResponse = withContext(Dispatchers.IO) {
                    val authRequest = vpRequestState.authRequest
                        ?: throw IllegalStateException("Authorization request is required")
                    vpTokenService.sendVpTokenToVerifier(vpToken, vpRequestState.responseUri, authRequest)
                }

                handleVerifierResponse(serverResponse)
            } catch (e: Exception) {
                Log.e("WalletApp", "Error sending mDoc VP Token", e)
                _events.send(WalletEvent.ShowSnackbar("❌ Error: ${e.message}"))
            }
        }
    }

    // --- Proximity presentation ---

    fun startProximityPresentation(activity: FragmentActivity) {
        val service = ProximityPresentationService(activity.applicationContext)
        proximityService = service
        proximityState = ProximityState(isActive = true, status = "Initializing...")

        service.startQrEngagement { event ->
            viewModelScope.launch {
                when (event) {
                    is ProximityPresentationService.ProximityEvent.QrCodeReady -> {
                        proximityState = proximityState.copy(
                            qrContent = event.qrContent,
                            status = "Scan this QR code with verifier device"
                        )
                    }
                    is ProximityPresentationService.ProximityEvent.Connecting -> {
                        proximityState = proximityState.copy(status = "Connecting...")
                    }
                    is ProximityPresentationService.ProximityEvent.Connected -> {
                        proximityState = proximityState.copy(status = "Connected, waiting for request...")
                    }
                    is ProximityPresentationService.ProximityEvent.RequestReceived -> {
                        proximityState = proximityState.copy(
                            status = "Request received",
                            requestedClaims = event.parsedRequest.allRequestedElements,
                            parsedRequest = event.parsedRequest
                        )
                    }
                    is ProximityPresentationService.ProximityEvent.ResponseSent -> {
                        proximityState = proximityState.copy(status = "Presentation complete")
                        _events.send(WalletEvent.ShowSnackbar("Proximity presentation complete"))
                    }
                    is ProximityPresentationService.ProximityEvent.Disconnected -> {
                        proximityState = proximityState.copy(status = "Disconnected")
                    }
                    is ProximityPresentationService.ProximityEvent.Error -> {
                        Log.e("WalletApp", "Proximity error", event.error)
                        proximityState = proximityState.copy(
                            status = "Error: ${event.error.message}"
                        )
                        _events.send(WalletEvent.ShowSnackbar("Proximity error: ${event.error.message}"))
                    }
                }
            }
        }
    }

    fun submitProximityResponse(selectedClaims: List<String>) {
        proximityState = proximityState.copy(requestedClaims = null, status = "Sending response...")

        viewModelScope.launch {
            try {
                val credKey = selectedCredentialKey
                    ?: throw IllegalStateException("No credential selected for proximity presentation")
                val bundle = withContext(Dispatchers.IO) {
                    issuanceService.getStoredCredentialBundle(credKey)
                } ?: throw IllegalStateException("Credential not found: $credKey")
                val storedCredential = bundle.rawCredential
                val sessionTranscript = proximityService?.sessionTranscript
                    ?: throw IllegalStateException("Session transcript not available")

                val responseBytes = withContext(Dispatchers.Default) {
                    DeviceResponseBuilder.buildBytes(
                        credential = storedCredential,
                        selectedClaims = selectedClaims,
                        sessionTranscript = sessionTranscript
                    )
                }

                proximityService?.sendResponse(responseBytes)
            } catch (e: Exception) {
                Log.e("WalletApp", "Error sending proximity response", e)
                proximityState = proximityState.copy(status = "Error: ${e.message}")
                _events.send(WalletEvent.ShowSnackbar("❌ Error: ${e.message}"))
            }
        }
    }

    fun stopProximityPresentation() {
        proximityService?.stopPresentation()
        proximityService = null
        proximityState = ProximityState()
    }

    fun flattenClaimsForDisplay(claims: Map<String, Any>): Map<String, String> {
        return issuanceService.flattenClaimsForDisplay(claims)
    }

    // --- Private helpers ---

    private fun addOrReplaceCredentialInList(credentialKey: String) {
        try {
            val bundle = issuanceService.getStoredCredentialBundle(credentialKey) ?: return
            val claims = issuanceService.decodeCredential(bundle.rawCredential, bundle.format)
            val newState = CredentialUiState(
                credentialKey = credentialKey,
                credentialFormat = bundle.format,
                claims = claims,
                claimResolver = ClaimMetadataResolver.fromNullable(bundle.claimsMetadata),
                credentialDisplayName = bundle.displayMetadata?.firstOrNull()?.name
            )
            credentialList = credentialList.filter { it.credentialKey != credentialKey } + newState
        } catch (e: Exception) {
            Log.e("WalletApp", "Error loading credential after issuance: $credentialKey", e)
        }
    }

    private suspend fun startAuthorizationFlowWithPar(
        activity: FragmentActivity,
        onError: (String) -> Unit
    ) {
        val context = activity.applicationContext
        val codeChallenge = PkceManager.generateAndStoreCodeChallenge(context)

        val parResult = withContext(Dispatchers.IO) {
            issuanceService.pushAuthorizationRequest(codeChallenge)
        }

        if (parResult.isFailure) {
            val errorMessage = parResult.exceptionOrNull()?.message ?: "Unknown PAR error"
            Log.e("WalletApp", "PAR request failed: $errorMessage")
            onError("PAR request failed: $errorMessage")
            return
        }

        val requestUri = parResult.getOrThrow()
        Log.d("WalletApp", "PAR success, received request_uri: $requestUri")

        val authorizationUri = Uri.Builder()
            .scheme("http")
            .encodedAuthority(AppConfig.AUTH_SERVER_HOST)
            .path("/oauth2/authorize")
            .appendQueryParameter("client_id", AppConfig.CLIENT_ID)
            .appendQueryParameter("request_uri", requestUri)
            .build()

        Log.d("WalletApp", "Redirecting to authorization: $authorizationUri")
        val intent = Intent(Intent.ACTION_VIEW, authorizationUri)
        activity.startActivity(intent)
    }

    private fun startAuthorizationFlowLegacy(activity: FragmentActivity) {
        val context = activity.applicationContext
        val codeChallenge = PkceManager.generateAndStoreCodeChallenge(context)

        val authorizationUri = Uri.Builder()
            .scheme("http")
            .encodedAuthority(AppConfig.AUTH_SERVER_HOST)
            .path("/oauth2/authorize")
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", AppConfig.CLIENT_ID)
            .appendQueryParameter("scope", AppConfig.SCOPE)
            .appendQueryParameter("redirect_uri", AppConfig.REDIRECT_URI)
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()

        val intent = Intent(Intent.ACTION_VIEW, authorizationUri)
        activity.startActivity(intent)
    }

    /**
     * Returns the credential key (not the raw credential) on success.
     */
    private suspend fun submitCredentialRequest(
        activity: FragmentActivity,
        credentialConfig: com.example.eudiwemu.dto.CredentialConfiguration? = null,
        format: String = AppConfig.FORMAT_SD_JWT
    ): Result<Pair<String, String>> {
        return try {
            val context = activity.applicationContext
            val prefs = getEncryptedPrefs(context, activity)
            val accessToken = prefs.getString("access_token", null)

            if (accessToken.isNullOrEmpty()) {
                return Result.failure(Exception("❌ Access token is missing!"))
            }

            val storedWua = wuaService.getStoredWua()
            if (storedWua.isNullOrEmpty()) {
                return Result.failure(Exception("❌ WUA not found - wallet not activated!"))
            }

            val nonce = issuanceService.getNonce(accessToken)
            val jwtProof = issuanceService.createJwtProof(nonce, storedWua)
            issuanceService.requestCredential(
                accessToken, jwtProof, credentialConfig, format
            )

            val configId = if (format == AppConfig.FORMAT_MSO_MDOC)
                AppConfig.MDOC_CREDENTIAL_CONFIG_ID else AppConfig.SD_JWT_CREDENTIAL_CONFIG_ID
            val credentialKey = AppConfig.extractCredentialKey(configId)

            Result.success("✅ VC stored securely" to credentialKey)
        } catch (e: Exception) {
            Log.e("WalletApp", "Error requesting VC", e)
            Result.failure(e)
        }
    }

    private suspend fun handleVerifierResponse(serverResponse: String) {
        try {
            val jsonResponse = JSONObject(serverResponse)
            val redirectUri: String? = jsonResponse.optString("redirect_uri", "")

            if (!redirectUri.isNullOrEmpty()) {
                _events.send(WalletEvent.OpenBrowser(redirectUri))
                _events.send(WalletEvent.ShowSnackbar("✅ Verification successful - opening result"))
            } else {
                _events.send(WalletEvent.ShowSnackbar("📋 Verifier Response: $serverResponse"))
            }
        } catch (e: Exception) {
            _events.send(WalletEvent.ShowSnackbar("📋 Verifier Response: $serverResponse"))
        }
    }

    private fun isAuthError(errorMessage: String): Boolean {
        val lowerMsg = errorMessage.lowercase()
        return lowerMsg.contains("401") ||
                lowerMsg.contains("unauthorized") ||
                lowerMsg.contains("expired") ||
                lowerMsg.contains("invalid_token") ||
                lowerMsg.contains("invalid token")
    }
}
