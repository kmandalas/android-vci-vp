package com.example.eudiwemu

import android.app.Application
import android.util.Log
import com.example.eudiwemu.di.appModule
import com.example.eudiwemu.security.FixedDebugAppCheckProviderFactory
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class WalletApplication : Application() {

    private companion object {
        private const val TAG = "WalletApplication"
    }

    override fun onCreate() {
        super.onCreate()

        // Initialize Firebase + App Check
        initFirebaseAppCheck()

        // Start Koin
        startKoin {
            androidLogger()
            androidContext(this@WalletApplication)
            modules(appModule)
        }
    }

    private fun initFirebaseAppCheck() {
        try {
            FirebaseApp.initializeApp(this)
            val appCheck = FirebaseAppCheck.getInstance()
            if (BuildConfig.DEBUG) {
                // Use a fixed, pre-registered debug token across all devices.
                // Register this UUID once in Firebase Console → App Check → Apps → Manage debug tokens.
                val fixedDebugToken = BuildConfig.APP_CHECK_DEBUG_TOKEN
                appCheck.installAppCheckProviderFactory(FixedDebugAppCheckProviderFactory(fixedDebugToken))
                Log.d(TAG, "App Check initialized with fixed debug token provider")
            } else {
                appCheck.installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
                Log.d(TAG, "App Check initialized with Play Integrity provider")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Firebase App Check initialization failed (app continues without it)", e)
        }
    }
}
