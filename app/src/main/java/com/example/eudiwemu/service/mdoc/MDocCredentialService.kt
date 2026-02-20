package com.example.eudiwemu.service.mdoc

import android.util.Base64
import android.util.Log
import com.authlete.cbor.CBORBoolean
import com.authlete.cbor.CBORByteArray
import com.authlete.cbor.CBORDecoder
import com.authlete.cbor.CBORDouble
import com.authlete.cbor.CBORInteger
import com.authlete.cbor.CBORItemList
import com.authlete.cbor.CBORLong
import com.authlete.cbor.CBORPairList
import com.authlete.cbor.CBORString
import com.authlete.cbor.CBORTaggedItem
import com.authlete.cose.COSESign1

data class MDocDecodeResult(
    val claims: Map<String, Any>,
    val issuedAt: Long? = null,
    val expiresAt: Long? = null
)

object MDocCredentialService {

    private const val TAG = "MDocCredentialService"

    fun decode(credential: String): MDocDecodeResult {
        Log.d(TAG, "Decoding mDoc credential...")
        try {
            val cborBytes = Base64.decode(credential, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

            val decoder = CBORDecoder(cborBytes)
            val issuerSignedMap = decoder.next() as CBORPairList

            // Extract claims from nameSpaces
            val nameSpacesPair = issuerSignedMap.findByKey("nameSpaces")
                ?: throw IllegalStateException("nameSpaces not found in IssuerSigned")
            val nameSpaces = nameSpacesPair.value as CBORPairList

            val claims = mutableMapOf<String, Any>()

            for (nsEntry in nameSpaces.pairs) {
                val items = nsEntry.value as CBORItemList
                for (itemObj in items.items) {
                    try {
                        val itemMap = decodeTag24Item(itemObj)

                        val elementIdPair = itemMap.findByKey("elementIdentifier")
                        val elementValPair = itemMap.findByKey("elementValue")

                        if (elementIdPair != null && elementValPair != null) {
                            val elementId = extractCborString(elementIdPair.value)
                            val elementValue = extractCborValue(elementValPair.value)
                            if (elementValue is Map<*, *>) {
                                for ((nestedKey, nestedValue) in elementValue) {
                                    claims[nestedKey.toString()] = nestedValue ?: ""
                                    Log.d(TAG, "mDoc claim (flattened): $nestedKey -> $nestedValue")
                                }
                            } else {
                                claims[elementId] = elementValue
                                Log.d(TAG, "mDoc claim: $elementId -> $elementValue")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Skipping malformed IssuerSignedItem", e)
                    }
                }
            }

            // Extract validity dates from issuerAuth → MSO → ValidityInfo
            val (issuedAt, expiresAt) = extractValidityFromIssuerSigned(issuerSignedMap)

            return MDocDecodeResult(claims, issuedAt, expiresAt)
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding mDoc credential", e)
            throw RuntimeException("Failed to decode mDoc credential: ${e.message}", e)
        }
    }

    private fun decodeTag24Item(item: Any): CBORPairList {
        return when (item) {
            is CBORTaggedItem -> {
                val content = item.tagContent
                if (content is CBORByteArray) {
                    val innerDecoder = CBORDecoder(content.value)
                    innerDecoder.next() as CBORPairList
                } else if (content is CBORPairList) {
                    content
                } else {
                    throw IllegalArgumentException("Unexpected tag-24 content: ${content?.javaClass}")
                }
            }
            is CBORPairList -> item
            else -> throw IllegalArgumentException("Unexpected IssuerSignedItem type: ${item.javaClass}")
        }
    }

    /**
     * Extract ValidityInfo dates from the already-parsed IssuerSigned map.
     * Uses Authlete's COSESign1.build() for typed COSE_Sign1 parsing instead of
     * manual CBORItemList indexing.
     */
    private fun extractValidityFromIssuerSigned(issuerSignedMap: CBORPairList): Pair<Long?, Long?> {
        return try {
            val issuerAuthPair = issuerSignedMap.findByKey("issuerAuth")
                ?: return null to null

            // Use Authlete's typed COSESign1 parser
            val coseSign1 = COSESign1.build(issuerAuthPair.value)
            val payload = coseSign1.payload ?: return null to null

            // Payload is the MSO bytes — unwrap Tag-24 if present
            val msoBytes = when (payload) {
                is CBORTaggedItem -> (payload.tagContent as CBORByteArray).value
                is CBORByteArray -> payload.value
                else -> return null to null
            }
            val msoRaw = CBORDecoder(msoBytes).next()
            val mso = when (msoRaw) {
                is CBORPairList -> msoRaw
                is CBORTaggedItem -> decodeTag24Item(msoRaw)
                else -> return null to null
            }

            val validityInfoPair = mso.findByKey("validityInfo") ?: return null to null
            val validityInfo = validityInfoPair.value as CBORPairList

            val signed = validityInfo.findByKey("signed")?.value
            val validUntil = validityInfo.findByKey("validUntil")?.value

            val issuedAt = parseCborDateToEpoch(signed)
            val expiresAt = parseCborDateToEpoch(validUntil)

            Log.d(TAG, "mDoc ValidityInfo: signed=$issuedAt, validUntil=$expiresAt")
            issuedAt to expiresAt
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract ValidityInfo from mDoc", e)
            null to null
        }
    }

    private fun parseCborDateToEpoch(value: Any?): Long? {
        return when (value) {
            is CBORTaggedItem -> {
                // Tag 0 = date-time string (ISO 8601), Tag 1 = epoch
                when (value.tagNumber.toInt()) {
                    0 -> {
                        val dateStr = extractCborString(value.tagContent)
                        try {
                            java.time.Instant.parse(dateStr).epochSecond
                        } catch (e: Exception) {
                            null
                        }
                    }
                    1 -> when (val content = value.tagContent) {
                        is CBORLong -> content.value
                        is CBORInteger -> content.value.toLong()
                        else -> null
                    }
                    else -> null
                }
            }
            is CBORString -> {
                try {
                    java.time.Instant.parse(value.value).epochSecond
                } catch (e: Exception) {
                    null
                }
            }
            else -> null
        }
    }

    private fun extractCborString(value: Any?): String {
        return when (value) {
            is CBORString -> value.value
            is String -> value
            else -> value?.toString() ?: ""
        }
    }

    private fun extractCborValue(value: Any?): Any {
        return when (value) {
            is CBORPairList -> {
                val map = mutableMapOf<String, Any>()
                for (pair in value.pairs) {
                    val key = extractCborString(pair.key)
                    val v = extractCborValue(pair.value)
                    map[key] = v
                }
                map
            }
            is CBORString -> value.value
            is CBORInteger -> value.value
            is CBORLong -> value.value
            is CBORDouble -> value.value
            is CBORBoolean -> value.value
            is CBORTaggedItem -> {
                extractCborValue(value.tagContent)
            }
            is CBORByteArray -> {
                Base64.encodeToString(value.value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            }
            null -> ""
            else -> value.toString()
        }
    }
}
