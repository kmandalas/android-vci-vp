package com.example.eudiwemu.ui.viewmodel

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.authlete.sd.Disclosure
import com.example.eudiwemu.config.AppConfig
import com.example.eudiwemu.dto.CredentialOffer
import com.example.eudiwemu.model.IssuerSession
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
import kotlinx.serialization.json.Json
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

    var isInitializing by mutableStateOf(true)
        private set

    private val json = Json { ignoreUnknownKeys = true }

    var bannerMessage by mutableStateOf<String?>(null)
        private set

    fun showBanner(message: String) { bannerMessage = message }
    fun clearBanner() { bannerMessage = null }

    private var proximityService: ProximityPresentationService? = null

    private val _events = Channel<WalletEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val selectedFormatValue: String
        get() = issuanceState.credentialConfigs[issuanceState.selectedValue]?.format
            ?: AppConfig.FORMAT_SD_JWT

    fun updateSelectedCredentialType(label: String, value: String) {
        issuanceState = issuanceState.copy(selectedLabel = label, selectedValue = value)
    }

    fun updateConformanceIssuerUrl(url: String) {
        issuanceState = issuanceState.copy(conformanceIssuerUrl = url)
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

                // Load local data first (no network needed)
                loadStoredAttestations()
                loadAllCredentials()
                isInitializing = false

                // Fetch issuer metadata last (network call, only affects dropdown)
                fetchIssuerMetadata()
            } catch (e: Exception) {
                Log.e("WalletApp", "Error initializing services: $e")
                isInitializing = false
            }
        }
    }

    private fun loadStoredAttestations() {
        // Load WUA (use raw retrieval to show expired attestations)
        val storedWua = wuaService.getStoredWuaRaw()
        if (!storedWua.isNullOrEmpty()) {
            try {
                attestationState = attestationState.copy(wuaInfo = wuaService.decodeWuaCredential(storedWua))
            } catch (e: Exception) {
                Log.e("WalletApp", "Error decoding stored WUA", e)
            }
        }

        // Load WIA (use raw retrieval to show expired attestations)
        val storedWia = wiaService.getStoredWiaRaw()
        if (!storedWia.isNullOrEmpty()) {
            try {
                attestationState = attestationState.copy(wiaInfo = wiaService.decodeWiaCredential(storedWia))
            } catch (e: Exception) {
                Log.e("WalletApp", "Error decoding stored WIA", e)
            }
        }
    }

    private suspend fun fetchIssuerMetadata() {
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
                    vct = claims?.get("vct") as? String,
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
                "openid-credential-offer" -> handleCredentialOfferDeepLink(uri, activity)
                else -> Log.w("WalletApp", "Unknown deep link scheme: ${uri.scheme}")
            }
        }
    }

    private suspend fun handleOAuthDeepLink(uri: Uri, activity: FragmentActivity) {
        val code = uri.getQueryParameter("code") ?: return
        Log.d("OAuth", "Authorization code: $code")

        val context = activity.applicationContext
        val prefs = getEncryptedPrefs(context, activity)
        val codeVerifier = PkceManager.getCodeVerifier(context)

        // Recover pending issuer session (if credential offer flow)
        val session = prefs.getString("pending_issuer_session", null)?.let {
            try { json.decodeFromString<IssuerSession>(it) } catch (e: Exception) { null }
        }

        val tokenResult = withContext(Dispatchers.IO) {
            if (session != null) {
                issuanceService.exchangeAuthorizationCodeForToken(
                    code, codeVerifier,
                    tokenUrl = session.tokenEndpoint,
                    authServerIssuer = session.authServerIssuer,
                    sendWia = session.sendWia
                )
            } else {
                issuanceService.exchangeAuthorizationCodeForToken(code, codeVerifier)
            }
        }

        if (tokenResult.isSuccess) {
            val tokenResponse = tokenResult.getOrNull()!!
            val accessToken = tokenResponse.access_token
            prefs.edit { putString("access_token", accessToken) }

            // Store c_nonce from token response if present (OID4VCI 1.0 Final)
            tokenResponse.c_nonce?.let { nonce ->
                prefs.edit { putString("pending_c_nonce", nonce) }
            }

            // Recover the format saved before the OAuth redirect (ViewModel is recreated)
            val pendingFormat = prefs.getString("pending_credential_format", null) ?: selectedFormatValue
            prefs.edit { remove("pending_credential_format") }
            Log.d("OAuth", "Access token saved! Using format: $pendingFormat")

            issuanceState = issuanceState.copy(isLoading = true)

            try {
                val vcResult = if (session != null) {
                    submitCredentialRequestWithSession(activity, session, accessToken)
                } else {
                    val configId = if (pendingFormat == AppConfig.FORMAT_MSO_MDOC)
                        AppConfig.MDOC_CREDENTIAL_CONFIG_ID else AppConfig.SD_JWT_CREDENTIAL_CONFIG_ID
                    val credentialConfig = issuanceState.credentialConfigs[configId]
                    submitCredentialRequest(activity, credentialConfig, pendingFormat)
                }

                issuanceState = issuanceState.copy(isLoading = false)
                // Clean up session
                prefs.edit { remove("pending_issuer_session") }

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
                prefs.edit { remove("pending_issuer_session") }
                _events.send(WalletEvent.ShowSnackbar("❌ Error requesting VC: ${e.message}"))
            }
        } else {
            Log.e("OAuth", "Failed to exchange code: ${tokenResult.exceptionOrNull()?.message}")
            _events.send(WalletEvent.ShowSnackbar("❌ Error: ${tokenResult.exceptionOrNull()?.message}"))
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
            val requestedVcts = requestObject.dcql_query.credentials.firstOrNull()?.meta?.vct_values
            Log.d("WalletApp", "VP DCQL requested format: $dcqlFormat, vct_values: $requestedVcts")

            // Find matching credential by format and VCT
            val matchedCredential = credentialList.find { cred ->
                cred.credentialFormat == dcqlFormat &&
                (requestedVcts == null || cred.vct in requestedVcts)
            }
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
                        issuanceState = issuanceState.copy(isLoading = false)
                        viewModelScope.launch { _events.send(WalletEvent.ShowSnackbar("❌ Authorization failed: $error")) }
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
                                viewModelScope.launch { _events.send(WalletEvent.ShowSnackbar("❌ Re-authentication failed: $error")) }
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
                        viewModelScope.launch { _events.send(WalletEvent.ShowSnackbar("❌ Authorization failed: $error")) }
                    }
                } else {
                    _events.send(WalletEvent.ShowSnackbar("❌ Error requesting VC: ${e.message}"))
                }
            }
        }
    }

    /**
     * Request a credential from an external issuer (conformance suite).
     * Builds a synthetic credential offer from the pasted issuer URL,
     * discovers endpoints, and starts the authorization code flow.
     */
    fun requestConformanceCredential(activity: FragmentActivity) {
        val issuerUrl = issuanceState.conformanceIssuerUrl.trim()
        if (issuerUrl.isBlank()) return

        issuanceState = issuanceState.copy(isLoading = true)
        viewModelScope.launch {
            try {
                // Discover issuer metadata to find credential configuration IDs
                val issuerMeta = withContext(Dispatchers.IO) {
                    issuanceService.fetchIssuerMetadata(issuerUrl)
                }
                val configIds = issuerMeta.credential_configurations_supported.keys.toList()
                if (configIds.isEmpty()) {
                    throw IllegalStateException("No credential configurations found at $issuerUrl")
                }
                Log.d("WalletApp", "Conformance issuer configs: $configIds")

                // Build a synthetic credential offer
                val offer = CredentialOffer(
                    credential_issuer = issuerUrl,
                    credential_configuration_ids = configIds,
                    grants = null
                )

                // Resolve and start the flow (pass pre-fetched metadata to avoid redundant fetch)
                val session = withContext(Dispatchers.IO) {
                    issuanceService.resolveCredentialOffer(offer, prefetchedIssuerMeta = issuerMeta)
                }

                val context = activity.applicationContext
                val prefs = getEncryptedPrefs(context, activity)
                prefs.edit {
                    remove("access_token")
                    putString("pending_issuer_session", json.encodeToString(IssuerSession.serializer(), session))
                    putString("pending_credential_format", AppConfig.FORMAT_SD_JWT)
                }

                val codeChallenge = PkceManager.generateAndStoreCodeChallenge(context)

                // Build authorization_details if AS supports RAR (RFC 9396)
                val authDetails = if (session.useAuthorizationDetails) {
                    issuanceService.buildAuthorizationDetails(
                        session.credentialConfigurationIds, session.credentialIssuerUrl
                    )
                } else null
                Log.d("WalletApp", "Conformance PAR: scope=${session.scope}, useRAR=${session.useAuthorizationDetails}")

                val parEndpoint = session.parEndpoint
                if (parEndpoint != null) {
                    val parResult = withContext(Dispatchers.IO) {
                        issuanceService.pushAuthorizationRequest(
                            codeChallenge = codeChallenge,
                            parUrl = parEndpoint,
                            scope = session.scope ?: configIds.first(),
                            authServerIssuer = session.authServerIssuer,
                            issuerState = session.issuerState,
                            sendWia = session.sendWia,
                            authorizationDetails = authDetails
                        )
                    }

                    if (parResult.isFailure) {
                        throw parResult.exceptionOrNull() ?: Exception("PAR failed")
                    }

                    val requestUri = parResult.getOrThrow()
                    val authorizationUri = session.authorizationEndpoint.toUri().buildUpon()
                        .appendQueryParameter("client_id", AppConfig.CLIENT_ID)
                        .appendQueryParameter("request_uri", requestUri)
                        .build()

                    activity.startActivity(Intent(Intent.ACTION_VIEW, authorizationUri))
                } else {
                    val authUriBuilder = session.authorizationEndpoint.toUri().buildUpon()
                        .appendQueryParameter("response_type", "code")
                        .appendQueryParameter("client_id", AppConfig.CLIENT_ID)
                        .appendQueryParameter("redirect_uri", AppConfig.REDIRECT_URI)
                        .appendQueryParameter("code_challenge", codeChallenge)
                        .appendQueryParameter("code_challenge_method", "S256")

                    authUriBuilder.appendQueryParameter("scope", session.scope ?: configIds.first())
                    authDetails?.let { authUriBuilder.appendQueryParameter("authorization_details", it) }

                    activity.startActivity(Intent(Intent.ACTION_VIEW, authUriBuilder.build()))
                }

                issuanceState = issuanceState.copy(isLoading = false)
            } catch (e: Exception) {
                Log.e("WalletApp", "Error in conformance credential request", e)
                issuanceState = issuanceState.copy(isLoading = false)
                _events.send(WalletEvent.ShowSnackbar("❌ Error: ${e.message}"))
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
                        _events.send(WalletEvent.ShowSnackbar("✅ Proximity presentation complete"))
                    }
                    is ProximityPresentationService.ProximityEvent.Disconnected -> {
                        proximityState = proximityState.copy(status = "Disconnected")
                    }
                    is ProximityPresentationService.ProximityEvent.Error -> {
                        Log.e("WalletApp", "Proximity error", event.error)
                        proximityState = proximityState.copy(
                            status = "Error: ${event.error.message}"
                        )
                        _events.send(WalletEvent.ShowSnackbar("❌ Proximity error: ${event.error.message}"))
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

    // --- Credential Offer Flow ---

    private suspend fun handleCredentialOfferDeepLink(uri: Uri, activity: FragmentActivity) {
        try {
            issuanceState = issuanceState.copy(isLoading = true)
            _events.send(WalletEvent.ShowSnackbar("📋 Processing credential offer..."))

            // Parse the credential_offer query parameter (by_value)
            val offerJson = uri.getQueryParameter("credential_offer")
                ?: throw IllegalArgumentException("Missing credential_offer parameter")
            val offer = json.decodeFromString<CredentialOffer>(offerJson)
            Log.d("WalletApp", "Credential offer from: ${offer.credential_issuer}, configs: ${offer.credential_configuration_ids}")

            // Resolve issuer + AS metadata into a session
            val session = withContext(Dispatchers.IO) {
                issuanceService.resolveCredentialOffer(offer)
            }
            Log.d("WalletApp", "Resolved session: token=${session.tokenEndpoint}, cred=${session.credentialEndpoint}, sendWia=${session.sendWia}")

            // Persist session for recovery after OAuth redirect
            val context = activity.applicationContext
            val prefs = getEncryptedPrefs(context, activity)
            val sessionJson = json.encodeToString(IssuerSession.serializer(), session)
            prefs.edit {
                putString("pending_issuer_session", sessionJson)
                putString("pending_credential_format", AppConfig.FORMAT_SD_JWT)
            }

            // Start authorization flow with PAR (or direct authorize)
            val codeChallenge = PkceManager.generateAndStoreCodeChallenge(context)

            // Build authorization_details if AS supports it (RAR / RFC 9396)
            val authDetails = if (session.useAuthorizationDetails) {
                issuanceService.buildAuthorizationDetails(
                    session.credentialConfigurationIds, session.credentialIssuerUrl
                )
            } else null

            val parEndpoint = session.parEndpoint
            if (parEndpoint != null) {
                val parResult = withContext(Dispatchers.IO) {
                    issuanceService.pushAuthorizationRequest(
                        codeChallenge = codeChallenge,
                        parUrl = parEndpoint,
                        scope = session.scope ?: AppConfig.SCOPE,
                        authServerIssuer = session.authServerIssuer,
                        issuerState = session.issuerState,
                        sendWia = session.sendWia,
                        authorizationDetails = authDetails
                    )
                }

                if (parResult.isFailure) {
                    throw parResult.exceptionOrNull() ?: Exception("PAR failed")
                }

                val requestUri = parResult.getOrThrow()
                val authorizationUri = session.authorizationEndpoint.toUri().buildUpon()
                    .appendQueryParameter("client_id", AppConfig.CLIENT_ID)
                    .appendQueryParameter("request_uri", requestUri)
                    .build()

                Log.d("WalletApp", "Redirecting to suite authorization: $authorizationUri")
                activity.startActivity(Intent(Intent.ACTION_VIEW, authorizationUri))
            } else {
                // No PAR — direct authorization request
                val authUriBuilder = session.authorizationEndpoint.toUri().buildUpon()
                    .appendQueryParameter("response_type", "code")
                    .appendQueryParameter("client_id", AppConfig.CLIENT_ID)
                    .appendQueryParameter("redirect_uri", AppConfig.REDIRECT_URI)
                    .appendQueryParameter("code_challenge", codeChallenge)
                    .appendQueryParameter("code_challenge_method", "S256")

                authUriBuilder.appendQueryParameter("scope", session.scope ?: AppConfig.SCOPE)
                authDetails?.let { authUriBuilder.appendQueryParameter("authorization_details", it) }
                session.issuerState?.let { authUriBuilder.appendQueryParameter("issuer_state", it) }

                Log.d("WalletApp", "Redirecting to authorization (no PAR): ${authUriBuilder.build()}")
                activity.startActivity(Intent(Intent.ACTION_VIEW, authUriBuilder.build()))
            }

            issuanceState = issuanceState.copy(isLoading = false)
        } catch (e: Exception) {
            Log.e("WalletApp", "Error handling credential offer", e)
            issuanceState = issuanceState.copy(isLoading = false)
            _events.send(WalletEvent.ShowSnackbar("❌ Error: ${e.message}"))
        }
    }

    // --- Private helpers ---

    /**
     * Submit credential request using a dynamically discovered IssuerSession.
     * Used for credential offer flow (conformance suite, external issuers).
     */
    private suspend fun submitCredentialRequestWithSession(
        activity: FragmentActivity,
        session: IssuerSession,
        accessToken: String
    ): Result<Pair<String, String>> {
        return try {
            // Use WUA only for our own backend; conformance suite doesn't support key_attestation
            val storedWua: String? = null  // Dynamic sessions skip WUA for now

            val context = activity.applicationContext
            val prefs = getEncryptedPrefs(context, activity)

            // Use c_nonce from token response if available, otherwise fetch from nonce endpoint
            val nonce = prefs.getString("pending_c_nonce", null)?.also {
                prefs.edit { remove("pending_c_nonce") }
                Log.d("WalletApp", "Using c_nonce from token response")
            } ?: withContext(Dispatchers.IO) {
                Log.d("WalletApp", "Fetching nonce from: ${session.nonceEndpoint}")
                issuanceService.getNonce(session.nonceEndpoint)
            }

            val credentialConfigId = session.credentialConfigurationIds.firstOrNull()
                ?: AppConfig.SD_JWT_CREDENTIAL_CONFIG_ID
            val jwtProof = issuanceService.createJwtProof(
                nonce, wuaJwt = storedWua, audience = session.credentialIssuerUrl
            )

            withContext(Dispatchers.IO) {
                issuanceService.requestCredential(
                    accessToken = accessToken,
                    jwtProof = jwtProof,
                    credentialUrl = session.credentialEndpoint,
                    credentialConfigId = credentialConfigId,
                    displayName = session.credentialDisplayName
                )
            }

            val credentialKey = AppConfig.extractCredentialKey(credentialConfigId)
            Result.success("✅ Credential issued successfully" to credentialKey)
        } catch (e: Exception) {
            Log.e("WalletApp", "Error in session-based credential request", e)
            Result.failure(e)
        }
    }

    private fun addOrReplaceCredentialInList(credentialKey: String) {
        try {
            val bundle = issuanceService.getStoredCredentialBundle(credentialKey) ?: return
            val claims = issuanceService.decodeCredential(bundle.rawCredential, bundle.format)
            val newState = CredentialUiState(
                credentialKey = credentialKey,
                credentialFormat = bundle.format,
                vct = claims?.get("vct") as? String,
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
                return Result.failure(Exception("WUA not found - wallet not activated!"))
            }

            // Use c_nonce from token response if available, otherwise fetch from nonce endpoint
            val nonce = prefs.getString("pending_c_nonce", null)?.also {
                prefs.edit { remove("pending_c_nonce") }
                Log.d("WalletApp", "Using c_nonce from token response")
            } ?: issuanceService.getNonce()
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
