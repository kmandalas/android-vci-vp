package dev.kmandalas.wallet.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import dev.kmandalas.wallet.data.entity.TransactionLogEntry

@Dao
interface TransactionLogDao {
    @Insert
    suspend fun insert(entry: TransactionLogEntry)

    @Insert
    suspend fun insertAll(entries: List<TransactionLogEntry>)

    @Query("SELECT * FROM transaction_log ORDER BY timestamp DESC")
    suspend fun getAll(): List<TransactionLogEntry>

    @Query("DELETE FROM transaction_log")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM transaction_log")
    suspend fun count(): Int
}
