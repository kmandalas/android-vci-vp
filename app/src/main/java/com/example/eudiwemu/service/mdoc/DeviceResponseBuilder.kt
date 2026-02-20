package com.example.eudiwemu.service.mdoc

import android.util.Base64
import android.util.Log
import com.authlete.cbor.CBORByteArray
import com.authlete.cbor.CBORDecoder
import com.authlete.cbor.CBORInteger
import com.authlete.cbor.CBORItem
import com.authlete.cbor.CBORItemList
import com.authlete.cbor.CBORNull
import com.authlete.cbor.CBORPair
import com.authlete.cbor.CBORPairList
import com.authlete.cbor.CBORString
import com.authlete.cbor.CBORTaggedItem
import com.authlete.cose.COSEProtectedHeaderBuilder
import com.authlete.cose.COSESign1
import com.authlete.cose.COSEUnprotectedHeader
import com.authlete.cose.SigStructureBuilder
import com.authlete.mdoc.DeviceAuth
import com.authlete.mdoc.DeviceNameSpaces
import com.authlete.mdoc.DeviceNameSpacesBytes
import com.authlete.mdoc.DeviceSigned
import com.nimbusds.jose.crypto.impl.ECDSA
import com.example.eudiwemu.config.AppConfig
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature

/**
 * Builds the complete DeviceResponse CBOR structure for mDoc VP presentation.
 *
 * Uses Authlete's typed mdoc classes (DeviceResponse, Document, IssuerSigned,
 * DeviceSigned, DeviceAuth, DeviceNameSpaces) for structured CBOR construction.
 */
object DeviceResponseBuilder {

    private const val TAG = "DeviceResponseBuilder"

    /**
     * Build a DeviceResponse from a stored IssuerSigned credential, returning raw CBOR bytes.
     * Used by proximity (BLE) presentation where DeviceRetrievalHelper needs raw bytes.
     *
     * @param credential         Base64url-encoded IssuerSigned CBOR
     * @param selectedClaims     List of elementIdentifier names to include
     * @param sessionTranscript  CBOR-encoded SessionTranscript bytes
     * @param keyAlias           Android Keystore alias for signing
     * @return Raw DeviceResponse CBOR bytes
     */
    fun buildBytes(
        credential: String,
        selectedClaims: List<String>,
        sessionTranscript: ByteArray,
        keyAlias: String = AppConfig.WUA_KEY_ALIAS
    ): ByteArray {
        // Step 1: Decode stored IssuerSigned CBOR
        val issuerSignedBytes = Base64.decode(credential, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val decoder = CBORDecoder(issuerSignedBytes)
        val issuerSignedMap = decoder.next() as CBORPairList

        // Step 2: Extract issuerAuth (keep as-is)
        val issuerAuthPair = issuerSignedMap.findByKey("issuerAuth")
            ?: throw IllegalStateException("issuerAuth not found in IssuerSigned")
        val issuerAuth = issuerAuthPair.value as CBORItem

        // Step 3: Extract and filter nameSpaces
        val nameSpacesPair = issuerSignedMap.findByKey("nameSpaces")
            ?: throw IllegalStateException("nameSpaces not found in IssuerSigned")
        val nameSpaces = nameSpacesPair.value as CBORPairList

        val filteredNameSpaces = filterNameSpaces(nameSpaces, selectedClaims)

        // Step 4: Build DeviceNameSpacesBytes using Authlete's typed classes
        val deviceNameSpacesBytes = DeviceNameSpacesBytes(DeviceNameSpaces(emptyList()))

        // Step 5: Build DeviceAuthentication and sign it
        val deviceSignature = buildDeviceSignature(
            sessionTranscript, deviceNameSpacesBytes, keyAlias
        )

        // Step 6: Assemble DeviceSigned using Authlete's typed classes
        val deviceSigned = DeviceSigned(deviceNameSpacesBytes, DeviceAuth(deviceSignature))

        // IssuerSigned kept as raw CBORPairList (reconstructed from decoded+filtered data)
        val issuerSigned = CBORPairList(listOf(
            CBORPair(CBORString("nameSpaces"), filteredNameSpaces),
            CBORPair(CBORString("issuerAuth"), issuerAuth)
        ))

        // Document as raw CBORPairList (IssuerSigned is raw, can't use typed Document)
        val document = CBORPairList(listOf(
            CBORPair(CBORString("docType"), CBORString(AppConfig.MDOC_DOC_TYPE)),
            CBORPair(CBORString("issuerSigned"), issuerSigned),
            CBORPair(CBORString("deviceSigned"), deviceSigned)
        ))

        // Step 7: Wrap in DeviceResponse using Authlete's typed class via its CBORPairList base
        val deviceResponse = CBORPairList(listOf(
            CBORPair(CBORString("version"), CBORString("1.0")),
            CBORPair(CBORString("documents"), CBORItemList(document)),
            CBORPair(CBORString("status"), CBORInteger(0))
        ))

        // Step 8: Encode and return raw bytes
        return deviceResponse.encode()
    }

    /**
     * Build a DeviceResponse from a stored IssuerSigned credential.
     * Used by OID4VP online flow where the VP token is base64url-encoded.
     *
     * @param credential         Base64url-encoded IssuerSigned CBOR
     * @param selectedClaims     List of elementIdentifier names to include
     * @param sessionTranscript  CBOR-encoded SessionTranscript bytes
     * @param keyAlias           Android Keystore alias for signing
     * @return Base64url-encoded DeviceResponse CBOR
     */
    fun build(
        credential: String,
        selectedClaims: List<String>,
        sessionTranscript: ByteArray,
        keyAlias: String = AppConfig.WUA_KEY_ALIAS
    ): String {
        val responseBytes = buildBytes(credential, selectedClaims, sessionTranscript, keyAlias)
        return Base64.encodeToString(responseBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /**
     * Filter nameSpaces to only include IssuerSignedItems matching selectedClaims.
     */
    private fun filterNameSpaces(
        nameSpaces: CBORPairList,
        selectedClaims: List<String>
    ): CBORPairList {
        val filteredPairs = mutableListOf<com.authlete.cbor.CBORPair>()

        for (nsEntry in nameSpaces.pairs) {
            val items = nsEntry.value as CBORItemList
            val filteredItems = mutableListOf<CBORItem>()

            for (itemObj in items.items) {
                try {
                    val elementId = extractElementIdentifier(itemObj)
                    if (elementId in selectedClaims) {
                        filteredItems.add(itemObj)
                        Log.d(TAG, "Including claim: $elementId")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping unreadable IssuerSignedItem", e)
                }
            }

            if (filteredItems.isNotEmpty()) {
                filteredPairs.add(com.authlete.cbor.CBORPair(nsEntry.key as CBORItem, CBORItemList(filteredItems)))
            }
        }

        return CBORPairList(filteredPairs)
    }

    /**
     * Extract elementIdentifier from a (possibly tag-24 wrapped) IssuerSignedItem.
     */
    private fun extractElementIdentifier(item: CBORItem): String {
        val itemMap = when (item) {
            is CBORTaggedItem -> {
                val content = item.tagContent
                if (content is CBORByteArray) {
                    val innerDecoder = CBORDecoder(content.value)
                    innerDecoder.next() as CBORPairList
                } else {
                    content as CBORPairList
                }
            }
            is CBORPairList -> item
            else -> throw IllegalArgumentException("Unexpected item type: ${item.javaClass}")
        }

        val elementIdPair = itemMap.findByKey("elementIdentifier")
            ?: throw IllegalStateException("elementIdentifier not found")

        return when (val v = elementIdPair.value) {
            is CBORString -> v.value
            else -> v.toString()
        }
    }

    /**
     * Build COSE_Sign1 deviceSignature over DeviceAuthentication.
     *
     * DeviceAuthentication = ["DeviceAuthentication", SessionTranscript, docType, DeviceNameSpacesBytes]
     *
     * Uses Authlete's COSE API (SigStructureBuilder, COSESign1) for structured construction.
     * COSE_Sign1 uses detached payload (null in structure).
     */
    private fun buildDeviceSignature(
        sessionTranscript: ByteArray,
        deviceNameSpacesBytes: DeviceNameSpacesBytes,
        keyAlias: String
    ): COSESign1 {
        // Step 1: Parse SessionTranscript back to CBOR item
        val stDecoder = CBORDecoder(sessionTranscript)
        val sessionTranscriptItem = stDecoder.next() as CBORItem

        // Step 2: Build DeviceAuthentication array
        val deviceAuthentication = CBORItemList(
            CBORString("DeviceAuthentication"),
            sessionTranscriptItem,
            CBORString(AppConfig.MDOC_DOC_TYPE),
            deviceNameSpacesBytes
        )

        // Step 2.1: Wrap as DeviceAuthenticationBytes = #6.24(bstr .cbor DeviceAuthentication)
        // Per ISO 18013-5, the COSE payload is the tag-24 wrapped encoding, not the raw array.
        val innerBytes = deviceAuthentication.encode()
        val deviceAuthenticationBytes = CBORTaggedItem(24, CBORByteArray(innerBytes)).encode()

        Log.d(TAG, "DeviceAuthenticationBytes CBOR size: ${deviceAuthenticationBytes.size} bytes")

        // Step 3: Build protected header {1: -7} (ES256) using Authlete's builder
        val protectedHeader = COSEProtectedHeaderBuilder()
            .alg(-7)  // ES256
            .build()

        // Step 4: Build Sig_structure1 using Authlete's builder
        val sigStructure = SigStructureBuilder()
            .signature1()
            .bodyAttributes(protectedHeader)
            .payload(deviceAuthenticationBytes)
            .build()

        // Step 5: Sign with Android Keystore (opaque keys require java.security.Signature)
        val sigStructureBytes = sigStructure.encode()
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val privateKey = keyStore.getKey(keyAlias, null) as PrivateKey
        val derSignature = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(sigStructureBytes)
            sign()
        }
        val signatureBytes = ECDSA.transcodeSignatureToConcat(derSignature, 64)

        Log.d(TAG, "DeviceAuth signature created (${signatureBytes.size} bytes)")

        // Step 6: Assemble COSE_Sign1 with detached payload [protectedHeader, {}, null, signature]
        return COSESign1(
            protectedHeader,
            COSEUnprotectedHeader(emptyList()),
            CBORNull.INSTANCE,
            CBORByteArray(signatureBytes)
        )
    }
}
