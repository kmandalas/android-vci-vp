package com.example.eudiwemu.service.mdoc

import android.content.Context
import android.util.Log
import com.android.identity.android.mdoc.deviceretrieval.DeviceRetrievalHelper
import com.android.identity.android.mdoc.engagement.QrEngagementHelper
import com.android.identity.android.mdoc.transport.DataTransport
import com.android.identity.android.mdoc.transport.DataTransportOptions
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPublicKey
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Orchestrates ISO 18013-5 proximity presentation over BLE.
 *
 * Flow:
 * 1. Generate ephemeral key pair + BLE connection method
 * 2. Start QR engagement → emit QR code content for display
 * 3. Verifier scans QR → BLE connection established
 * 4. Receive DeviceRequest → parse with [DeviceRequestParser]
 * 5. ViewModel builds DeviceResponse → call [sendResponse]
 * 6. Session terminates
 */
class ProximityPresentationService(private val context: Context) {

    companion object {
        private const val TAG = "ProximityPresentation"
        private const val SESSION_DATA_STATUS_SESSION_TERMINATION = 20L
    }

    sealed interface ProximityEvent {
        data class QrCodeReady(val qrContent: String) : ProximityEvent
        data object Connecting : ProximityEvent
        data object Connected : ProximityEvent
        data class RequestReceived(val parsedRequest: DeviceRequestParser.ParsedRequest) : ProximityEvent
        data object ResponseSent : ProximityEvent
        data object Disconnected : ProximityEvent
        data class Error(val error: Throwable) : ProximityEvent
    }

    private var qrEngagementHelper: QrEngagementHelper? = null
    private var deviceRetrievalHelper: DeviceRetrievalHelper? = null
    private val eDevicePrivateKey: EcPrivateKey by lazy { Crypto.createEcPrivateKey(EcCurve.P256) }
    private val executor: Executor = Executors.newSingleThreadExecutor()

    /** Session transcript from the BLE handshake, used for DeviceAuth signing. */
    val sessionTranscript: ByteArray?
        get() = deviceRetrievalHelper?.sessionTranscript

    /**
     * Start QR engagement: generates a QR code that a verifier can scan to initiate BLE connection.
     *
     * The QR content is available immediately after build() — no async callback needed.
     *
     * @param onEvent callback for proximity lifecycle events
     */
    fun startQrEngagement(onEvent: (ProximityEvent) -> Unit) {
        try {
            val bleUuid = UUID.randomUUID()

            val connectionMethods = listOf<MdocConnectionMethod>(
                MdocConnectionMethodBle(
                    supportsPeripheralServerMode = true,
                    supportsCentralClientMode = false,
                    peripheralServerModeUuid = bleUuid,
                    centralClientModeUuid = null
                )
            )

            val transportOptions = DataTransportOptions.Builder().build()

            val qrListener = object : QrEngagementHelper.Listener {
                override fun onDeviceConnecting() {
                    Log.d(TAG, "Device connecting...")
                    onEvent(ProximityEvent.Connecting)
                }

                override fun onDeviceConnected(transport: DataTransport) {
                    Log.d(TAG, "Device connected via ${transport.javaClass.simpleName}")

                    // Transition from QR engagement to device retrieval
                    val engagement = qrEngagementHelper!!

                    deviceRetrievalHelper = DeviceRetrievalHelper.Builder(
                        context,
                        createDeviceRetrievalListener(onEvent),
                        executor,
                        eDevicePrivateKey
                    )
                        .useForwardEngagement(transport, engagement.deviceEngagement, engagement.handover)
                        .build()

                    onEvent(ProximityEvent.Connected)
                }

                override fun onError(error: Throwable) {
                    Log.e(TAG, "QR engagement error", error)
                    onEvent(ProximityEvent.Error(error))
                }
            }

            val helper = QrEngagementHelper.Builder(
                context,
                eDevicePrivateKey.publicKey,
                transportOptions,
                qrListener,
                executor
            )
                .setConnectionMethods(connectionMethods)
                .build()

            qrEngagementHelper = helper

            // QR content is available immediately after build()
            val qrContent = helper.deviceEngagementUriEncoded
            Log.d(TAG, "QR engagement ready: ${qrContent.take(50)}...")
            onEvent(ProximityEvent.QrCodeReady(qrContent))

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start QR engagement", e)
            onEvent(ProximityEvent.Error(e))
        }
    }

    /**
     * Send the DeviceResponse bytes back to the verifier over BLE.
     *
     * @param responseBytes raw CBOR-encoded DeviceResponse
     */
    fun sendResponse(responseBytes: ByteArray) {
        val helper = deviceRetrievalHelper
            ?: throw IllegalStateException("DeviceRetrievalHelper not initialized")

        Log.d(TAG, "Sending DeviceResponse (${responseBytes.size} bytes)")
        helper.sendDeviceResponse(responseBytes, SESSION_DATA_STATUS_SESSION_TERMINATION)
    }

    /**
     * Stop the proximity presentation and release all resources.
     */
    fun stopPresentation() {
        Log.d(TAG, "Stopping proximity presentation")
        try {
            deviceRetrievalHelper?.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Error disconnecting device retrieval helper", e)
        }
        try {
            qrEngagementHelper?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing QR engagement helper", e)
        }
        deviceRetrievalHelper = null
        qrEngagementHelper = null
    }

    private fun createDeviceRetrievalListener(
        onEvent: (ProximityEvent) -> Unit
    ): DeviceRetrievalHelper.Listener {
        return object : DeviceRetrievalHelper.Listener {
            override fun onEReaderKeyReceived(eReaderKey: EcPublicKey) {
                Log.d(TAG, "eReader key received")
            }

            override fun onDeviceRequest(deviceRequestBytes: ByteArray) {
                Log.d(TAG, "DeviceRequest received (${deviceRequestBytes.size} bytes)")
                try {
                    val parsed = DeviceRequestParser.parse(deviceRequestBytes)
                    onEvent(ProximityEvent.RequestReceived(parsed))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse DeviceRequest", e)
                    onEvent(ProximityEvent.Error(e))
                }
            }

            override fun onDeviceDisconnected(transportSpecificTermination: Boolean) {
                Log.d(TAG, "Device disconnected (transportSpecific=$transportSpecificTermination)")
                onEvent(ProximityEvent.Disconnected)
            }

            override fun onError(error: Throwable) {
                Log.e(TAG, "Device retrieval error", error)
                onEvent(ProximityEvent.Error(error))
            }
        }
    }
}
