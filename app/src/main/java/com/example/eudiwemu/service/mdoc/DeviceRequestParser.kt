package com.example.eudiwemu.service.mdoc

import android.util.Log
import com.authlete.cbor.CBORBoolean
import com.authlete.cbor.CBORByteArray
import com.authlete.cbor.CBORDecoder
import com.authlete.cbor.CBORItem
import com.authlete.cbor.CBORItemList
import com.authlete.cbor.CBORPairList
import com.authlete.cbor.CBORString
import com.authlete.cbor.CBORTaggedItem

/**
 * Parses ISO 18013-5 DeviceRequest CBOR received over BLE proximity transport.
 *
 * DeviceRequest = {
 *   "version": "1.0",
 *   "docRequests": [{
 *     "itemsRequest": #6.24(bstr .cbor ItemsRequest),
 *     "readerAuth": COSE_Sign1  (optional)
 *   }]
 * }
 *
 * ItemsRequest = {
 *   "docType": "eu.europa.ec.eudi.pda1.1",
 *   "nameSpaces": {
 *     "<namespace>": {
 *       "<elementIdentifier>": <intentToRetain (bool)>,
 *       ...
 *     }
 *   }
 * }
 */
object DeviceRequestParser {

    private const val TAG = "DeviceRequestParser"

    data class ParsedRequest(
        val docType: String,
        val requestedItems: Map<String, List<String>>,
        val readerAuthPresent: Boolean
    ) {
        /** Flat list of all requested element identifiers across all namespaces. */
        val allRequestedElements: List<String>
            get() = requestedItems.values.flatten()
    }

    /**
     * Parse raw CBOR DeviceRequest bytes into a [ParsedRequest].
     *
     * @param deviceRequestBytes raw CBOR bytes from BLE transport
     * @return parsed request with docType, requested items per namespace, and readerAuth presence
     */
    fun parse(deviceRequestBytes: ByteArray): ParsedRequest {
        val decoder = CBORDecoder(deviceRequestBytes)
        val root = decoder.next() as? CBORPairList
            ?: throw IllegalStateException("DeviceRequest root is not a CBOR map")

        // Extract docRequests array
        val docRequestsItem = root.findByKey("docRequests")?.value as? CBORItemList
            ?: throw IllegalStateException("docRequests not found or not an array")

        // Process first docRequest (single-document presentation)
        val docRequest = docRequestsItem.items.firstOrNull() as? CBORPairList
            ?: throw IllegalStateException("docRequests array is empty")

        // Check for readerAuth presence
        val readerAuthPresent = docRequest.findByKey("readerAuth") != null
        if (readerAuthPresent) {
            Log.d(TAG, "readerAuth is present (not validated in demo mode)")
        }

        // Extract itemsRequest (tag-24 wrapped)
        val itemsRequestRaw = docRequest.findByKey("itemsRequest")?.value
            ?: throw IllegalStateException("itemsRequest not found in docRequest")

        val itemsRequest = unwrapItemsRequest(itemsRequestRaw)

        // Extract docType
        val docType = (itemsRequest.findByKey("docType")?.value as? CBORString)?.value
            ?: throw IllegalStateException("docType not found in ItemsRequest")

        // Extract nameSpaces
        val nameSpaces = itemsRequest.findByKey("nameSpaces")?.value as? CBORPairList
            ?: throw IllegalStateException("nameSpaces not found in ItemsRequest")

        val requestedItems = mutableMapOf<String, List<String>>()

        for (nsPair in nameSpaces.pairs) {
            val nsName = (nsPair.key as? CBORString)?.value ?: continue
            val nsElements = nsPair.value as? CBORPairList ?: continue

            val elementNames = nsElements.pairs.mapNotNull { elementPair ->
                (elementPair.key as? CBORString)?.value
            }

            if (elementNames.isNotEmpty()) {
                requestedItems[nsName] = elementNames
                Log.d(TAG, "Namespace '$nsName': ${elementNames.size} elements requested")
            }
        }

        Log.d(TAG, "Parsed DeviceRequest: docType=$docType, " +
                "${requestedItems.values.sumOf { it.size }} total elements, " +
                "readerAuth=$readerAuthPresent")

        return ParsedRequest(
            docType = docType,
            requestedItems = requestedItems,
            readerAuthPresent = readerAuthPresent
        )
    }

    /**
     * Unwrap itemsRequest which may be tag-24 wrapped (bstr containing CBOR).
     */
    private fun unwrapItemsRequest(item: Any): CBORPairList {
        return when (item) {
            is CBORTaggedItem -> {
                val content = item.tagContent
                if (content is CBORByteArray) {
                    CBORDecoder(content.value).next() as CBORPairList
                } else {
                    content as CBORPairList
                }
            }
            is CBORPairList -> item
            is CBORByteArray -> CBORDecoder(item.value).next() as CBORPairList
            else -> throw IllegalArgumentException("Unexpected itemsRequest type: ${item.javaClass}")
        }
    }
}
