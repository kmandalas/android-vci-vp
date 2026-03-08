package com.example.eudiwemu.security

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.provider.Settings
import androidx.biometric.BiometricManager
import com.example.eudiwemu.BuildConfig
import com.scottyab.rootbeer.RootBeer
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

enum class SecurityPostureLevel { LEVEL_1, LEVEL_2, LEVEL_3, LEVEL_4 }

data class PostureReport(
    val level: SecurityPostureLevel,
    val findings: List<String>
)

object DevicePostureService {

    private const val TAG = "DevicePostureService"

    fun evaluate(context: Context): PostureReport {
        val findings = mutableListOf<String>()

        // Level 4 (Critical) checks
        if (!BuildConfig.DEBUG) {
            if (isEmulator()) findings.add("Running on emulator")
            if (isDebuggerAttached(context)) findings.add("Debugger attached")
        }
        if (isRooted(context)) findings.add("Device is rooted")

        // Level 3 (High) checks
        if (isSecurityPatchOutdated()) findings.add("OS security patch is over 180 days old")
        if (!isBiometricEnrolled(context)) findings.add("No biometric authentication enrolled")

        // Level 2 (Moderate) checks
        if (!isScreenLockSet(context)) findings.add("No screen lock configured")
        if (isDeveloperOptionsEnabled(context)) findings.add("Developer options enabled")

        val level = when {
            findings.any { it in LEVEL_4_FINDINGS } -> SecurityPostureLevel.LEVEL_4
            findings.any { it in LEVEL_3_FINDINGS } -> SecurityPostureLevel.LEVEL_3
            findings.any { it in LEVEL_2_FINDINGS } -> SecurityPostureLevel.LEVEL_2
            else -> SecurityPostureLevel.LEVEL_1
        }

        return PostureReport(level, findings)
    }

    private val LEVEL_4_FINDINGS = setOf(
        "Running on emulator", "Debugger attached", "Device is rooted"
    )
    private val LEVEL_3_FINDINGS = setOf(
        "OS security patch is over 180 days old", "No biometric authentication enrolled"
    )
    private val LEVEL_2_FINDINGS = setOf(
        "No screen lock configured", "Developer options enabled"
    )

    private fun isRooted(context: Context): Boolean {
        return try {
            RootBeer(context).isRooted
        } catch (_: Exception) {
            false
        }
    }

    private fun isEmulator(): Boolean {
        return Build.FINGERPRINT.startsWith("generic") ||
                Build.FINGERPRINT.startsWith("unknown") ||
                Build.MODEL.contains("google_sdk") ||
                Build.MODEL.contains("Emulator") ||
                Build.MODEL.contains("Android SDK built for x86") ||
                Build.HARDWARE.contains("goldfish") ||
                Build.HARDWARE.contains("ranchu")
    }

    private fun isDebuggerAttached(context: Context): Boolean {
        if (Debug.isDebuggerConnected()) return true
        val appInfo = context.applicationInfo
        return (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun isSecurityPatchOutdated(): Boolean {
        return try {
            val patchDate = LocalDate.parse(Build.VERSION.SECURITY_PATCH, DateTimeFormatter.ISO_LOCAL_DATE)
            ChronoUnit.DAYS.between(patchDate, LocalDate.now()) > 180
        } catch (_: Exception) {
            false
        }
    }

    private fun isBiometricEnrolled(context: Context): Boolean {
        val result = BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        return result == BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun isScreenLockSet(context: Context): Boolean {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return keyguard.isDeviceSecure
    }

    private fun isDeveloperOptionsEnabled(context: Context): Boolean {
        return try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0
        } catch (_: Exception) {
            false
        }
    }
}
