package dev.kmandalas.wallet.data

import androidx.room.Database
import androidx.room.RoomDatabase
import dev.kmandalas.wallet.data.dao.TransactionLogDao
import dev.kmandalas.wallet.data.entity.TransactionLogEntry

@Database(entities = [TransactionLogEntry::class], version = 1, exportSchema = false)
abstract class WalletDatabase : RoomDatabase() {
    abstract fun transactionLogDao(): TransactionLogDao
}
