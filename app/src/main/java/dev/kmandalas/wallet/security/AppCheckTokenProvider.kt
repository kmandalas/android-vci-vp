package dev.kmandalas.wallet.security

import android.util.Log
import com.google.firebase.appcheck.FirebaseAppCheck
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object AppCheckTokenProvider {

    private const val TAG = "AppCheckTokenProvider"

    suspend fun getToken(): String? = suspendCancellableCoroutine { cont ->
        try {
            val task = FirebaseAppCheck.getInstance().getAppCheckToken(false)
            task.addOnSuccessListener { result ->
                cont.resume(result.token)
            }
            task.addOnFailureListener { e ->
                Log.w(TAG, "Failed to get App Check token", e)
                cont.resume(null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "App Check not available", e)
            cont.resume(null)
        }
    }
}
