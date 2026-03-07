package com.example.eudiwemu.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transaction_log")
data class TransactionLogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transactionType: String,
    val timestamp: Long = System.currentTimeMillis(),
    val credentialType: String? = null,
    val credentialFormat: String? = null,
    val credentialDisplayName: String? = null,
    val counterpartyName: String? = null,
    val attributeIds: String? = null,
    val status: String = "SUCCESS",
    val notes: String? = null
) {
    companion object {
        const val TYPE_ISSUANCE = "ISSUANCE"
        const val TYPE_PRESENTATION = "PRESENTATION"
        const val TYPE_PROXIMITY_PRESENTATION = "PROXIMITY_PRESENTATION"
        const val TYPE_DELETION = "DELETION"
    }
}
