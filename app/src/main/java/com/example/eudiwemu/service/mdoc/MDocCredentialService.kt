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

object MDocCredentialService {

    private const val TAG = "MDocCredentialService"

    fun decode(credential: String): Map<String, Any> {
        Log.d(TAG, "Decoding mDoc credential...")
        try {
            val cborBytes = Base64.decode(credential, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

            val decoder = CBORDecoder(cborBytes)
            val issuerSignedObj = decoder.next()
            val issuerSignedMap = issuerSignedObj as CBORPairList

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

            return claims
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
