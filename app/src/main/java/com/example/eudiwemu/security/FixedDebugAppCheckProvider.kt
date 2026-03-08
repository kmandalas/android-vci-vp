package com.example.eudiwemu.security

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.AppCheckProvider
import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.AppCheckToken
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Custom App Check provider for debug builds that exchanges a fixed, pre-registered
 * debug token with Firebase directly via the REST API — eliminating the need to register
 * a new token per device.
 *
 * Register [debugToken] once in Firebase Console → App Check → Apps → Manage debug tokens.
 */
class FixedDebugAppCheckProviderFactory(
    private val debugToken: String
) : AppCheckProviderFactory {
    override fun create(firebaseApp: FirebaseApp): AppCheckProvider =
        FixedDebugAppCheckProvider(firebaseApp, debugToken)
}

private class FixedDebugAppCheckProvider(
    private val firebaseApp: FirebaseApp,
    private val debugToken: String
) : AppCheckProvider {

    companion object {
        private const val TAG = "FixedDebugAppCheck"
    }

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json() }
    }

    override fun getToken(): Task<AppCheckToken> {
        val source = TaskCompletionSource<AppCheckToken>()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val projectId = requireNotNull(firebaseApp.options.projectId) { "Firebase projectId is null" }
                val appId = firebaseApp.options.applicationId
                val apiKey = firebaseApp.options.apiKey

                Log.d(TAG, "Exchanging fixed debug token with Firebase App Check")

                val response = httpClient.post(
                    "https://firebaseappcheck.googleapis.com/v1/projects/$projectId/apps/$appId:exchangeDebugToken"
                ) {
                    header("x-goog-api-key", apiKey)
                    contentType(ContentType.Application.Json)
                    setBody(mapOf("debug_token" to debugToken))
                }

                val json = response.body<JsonObject>()
                val token = json["token"]!!.jsonPrimitive.content
                val ttlStr = json["ttl"]?.jsonPrimitive?.content ?: "3600s"
                val ttlSeconds = ttlStr.removeSuffix("s").toLongOrNull() ?: 3600L

                Log.d(TAG, "App Check token obtained successfully (expires in ${ttlSeconds}s)")

                source.setResult(object : AppCheckToken() {
                    override fun getToken(): String = token
                    override fun getExpireTimeMillis(): Long = System.currentTimeMillis() + ttlSeconds * 1_000
                })
            } catch (e: Exception) {
                Log.e(TAG, "Failed to exchange App Check debug token", e)
                source.setException(e)
            }
        }
        return source.task
    }
}
