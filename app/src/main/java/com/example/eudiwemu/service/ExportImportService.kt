package com.example.eudiwemu.service

import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.example.eudiwemu.config.AppConfig
import com.example.eudiwemu.data.dao.TransactionLogDao
import com.example.eudiwemu.data.entity.TransactionLogEntry
import com.example.eudiwemu.security.getEncryptedPrefs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class ExportImportService(
    private val transactionLogDao: TransactionLogDao
) {
    companion object {
        private const val TAG = "ExportImportService"
        private const val PBKDF2_ITERATIONS = 210_000
        private const val SALT_LENGTH = 16
        private const val IV_LENGTH = 12
        private const val KEY_LENGTH = 256
        private const val GCM_TAG_LENGTH = 128
    }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Serializable
    data class ExportPayload(
        val version: Int = 1,
        val exportedAt: Long = System.currentTimeMillis(),
        val transactionLog: List<TransactionLogEntryDto>,
        val credentialMetadata: List<CredentialMetadataDto>
    )

    @Serializable
    data class TransactionLogEntryDto(
        val id: Long = 0,
        val transactionType: String,
        val timestamp: Long,
        val credentialType: String? = null,
        val credentialFormat: String? = null,
        val credentialDisplayName: String? = null,
        val counterpartyName: String? = null,
        val attributeIds: String? = null,
        val status: String = "SUCCESS",
        val notes: String? = null
    )

    @Serializable
    data class CredentialMetadataDto(
        val credentialKey: String,
        val format: String,
        val displayName: String? = null,
        val issuedAt: Long? = null,
        val expiresAt: Long? = null
    )

    data class ImportResult(
        val logEntriesRestored: Int,
        val credentialHints: List<CredentialMetadataDto>
    )

    suspend fun buildExportPayload(activity: FragmentActivity): ExportPayload {
        val logEntries = transactionLogDao.getAll().map { it.toDto() }

        val context = activity.applicationContext
        val prefs = getEncryptedPrefs(context, activity)
        val credentialIndex = prefs.getStringSet(AppConfig.STORED_CREDENTIAL_INDEX, emptySet()) ?: emptySet()

        val credentialMetadata = credentialIndex.mapNotNull { key ->
            val bundleJson = prefs.getString(key, null) ?: return@mapNotNull null
            try {
                val bundle = json.decodeFromString<com.example.eudiwemu.dto.StoredCredential>(bundleJson)
                CredentialMetadataDto(
                    credentialKey = key,
                    format = bundle.format,
                    displayName = bundle.displayMetadata?.firstOrNull()?.name,
                    issuedAt = bundle.issuedAt,
                    expiresAt = bundle.expiresAt
                )
            } catch (e: Exception) {
                Log.w(TAG, "Skipping credential $key during export: ${e.message}")
                null
            }
        }

        return ExportPayload(
            transactionLog = logEntries,
            credentialMetadata = credentialMetadata
        )
    }

    fun encrypt(payload: ExportPayload, password: String): ByteArray {
        val payloadJson = json.encodeToString(ExportPayload.serializer(), payload)
        val plaintext = payloadJson.toByteArray(Charsets.UTF_8)

        val salt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }

        val keySpec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH)
        val secretKey = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(keySpec)
        val aesKey = SecretKeySpec(secretKey.encoded, "AES")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val ciphertext = cipher.doFinal(plaintext)

        return salt + iv + ciphertext
    }

    fun decrypt(data: ByteArray, password: String): ExportPayload {
        require(data.size > SALT_LENGTH + IV_LENGTH) { "Invalid export file" }

        val salt = data.copyOfRange(0, SALT_LENGTH)
        val iv = data.copyOfRange(SALT_LENGTH, SALT_LENGTH + IV_LENGTH)
        val ciphertext = data.copyOfRange(SALT_LENGTH + IV_LENGTH, data.size)

        val keySpec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH)
        val secretKey = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(keySpec)
        val aesKey = SecretKeySpec(secretKey.encoded, "AES")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val plaintext = cipher.doFinal(ciphertext)

        return json.decodeFromString(ExportPayload.serializer(), String(plaintext, Charsets.UTF_8))
    }

    suspend fun importTransactionLog(entries: List<TransactionLogEntryDto>): Int {
        val roomEntries = entries.map { it.toEntity() }
        transactionLogDao.insertAll(roomEntries)
        return roomEntries.size
    }

    private fun TransactionLogEntry.toDto() = TransactionLogEntryDto(
        id = id,
        transactionType = transactionType,
        timestamp = timestamp,
        credentialType = credentialType,
        credentialFormat = credentialFormat,
        credentialDisplayName = credentialDisplayName,
        counterpartyName = counterpartyName,
        attributeIds = attributeIds,
        status = status,
        notes = notes
    )

    private fun TransactionLogEntryDto.toEntity() = TransactionLogEntry(
        id = 0,
        transactionType = transactionType,
        timestamp = timestamp,
        credentialType = credentialType,
        credentialFormat = credentialFormat,
        credentialDisplayName = credentialDisplayName,
        counterpartyName = counterpartyName,
        attributeIds = attributeIds,
        status = status,
        notes = notes
    )
}
