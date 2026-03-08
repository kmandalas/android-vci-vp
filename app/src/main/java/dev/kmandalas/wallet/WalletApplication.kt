package dev.kmandalas.wallet

import android.app.Application
import android.util.Log
import com.aheaditec.talsec_security.security.api.SuspiciousAppInfo
import com.aheaditec.talsec_security.security.api.Talsec
import com.aheaditec.talsec_security.security.api.TalsecConfig
import com.aheaditec.talsec_security.security.api.TalsecMode
import com.aheaditec.talsec_security.security.api.ThreatListener
import dev.kmandalas.wallet.di.appModule
import dev.kmandalas.wallet.security.FixedDebugAppCheckProviderFactory
import dev.kmandalas.wallet.security.FreeRaspThreatCollector
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

    private val threatDetected = object : ThreatListener.ThreatDetected() {
        override fun onRootDetected() {
            Log.w(TAG, "freeRASP: root detected")
            FreeRaspThreatCollector.report("Rooted device (freeRASP)")
        }

        override fun onDebuggerDetected() {
            Log.w(TAG, "freeRASP: debugger detected")
            FreeRaspThreatCollector.report("Debugger attached (freeRASP)")
        }

        override fun onEmulatorDetected() {
            Log.w(TAG, "freeRASP: emulator detected")
            FreeRaspThreatCollector.report("Running on emulator (freeRASP)")
        }

        override fun onHookDetected() {
            Log.w(TAG, "freeRASP: hook framework detected (Frida/Xposed)")
            FreeRaspThreatCollector.report("Frida/hook framework detected")
        }

        override fun onTamperDetected() {
            Log.w(TAG, "freeRASP: app tampering detected")
            FreeRaspThreatCollector.report("App tampering detected")
        }

        override fun onMalwareDetected(apps: List<SuspiciousAppInfo>) {
            Log.w(TAG, "freeRASP: malware/suspicious apps: ${apps.map { it.packageInfo.packageName }}")
            FreeRaspThreatCollector.report("Malware/suspicious app detected")
        }

        override fun onUntrustedInstallationSourceDetected() {
            Log.w(TAG, "freeRASP: untrusted installation source detected")
            FreeRaspThreatCollector.report("Untrusted installation source")
        }

        override fun onDeviceBindingDetected() {
            Log.w(TAG, "freeRASP: device binding issue detected")
        }

        override fun onObfuscationIssuesDetected() {
            Log.w(TAG, "freeRASP: obfuscation issues detected")
        }

        override fun onScreenshotDetected() {
            Log.d(TAG, "freeRASP: screenshot detected")
        }

        override fun onScreenRecordingDetected() {
            Log.d(TAG, "freeRASP: screen recording detected")
        }

        override fun onMultiInstanceDetected() {
            Log.w(TAG, "freeRASP: multi-instance detected")
        }

        override fun onUnsecureWifiDetected() {
            Log.d(TAG, "freeRASP: unsecure Wi-Fi detected")
        }

        override fun onTimeSpoofingDetected() {
            Log.w(TAG, "freeRASP: time spoofing detected")
        }

        override fun onLocationSpoofingDetected() {
            Log.d(TAG, "freeRASP: location spoofing detected")
        }

        override fun onAutomationDetected() {
            Log.w(TAG, "freeRASP: automation framework detected")
        }
    }

    private var threatListener: ThreatListener? = null

    override fun onCreate() {
        super.onCreate()

        // Initialize Firebase + App Check
        initFirebaseAppCheck()

        // Initialize freeRASP runtime protection
        initFreeRasp()

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

    private fun initFreeRasp() {
        try {
            val config = TalsecConfig.Builder(
                BuildConfig.APPLICATION_ID,
                arrayOf(BuildConfig.SIGNING_CERT_HASH)
            )
                .prod(!BuildConfig.DEBUG)  // false = emulator/debugger/signing checks disabled in debug
                .killOnBypass(false)       // posture L4 AlertDialog handles critical threats
                .build()
            Talsec.start(this, config, TalsecMode.BACKGROUND)
            threatListener = ThreatListener(threatDetected).also { it.registerListener(this) }
            Log.d(TAG, "freeRASP initialized (isProd=${!BuildConfig.DEBUG})")
        } catch (e: Exception) {
            Log.w(TAG, "freeRASP init failed: ${e.message}")
        }
    }
}
