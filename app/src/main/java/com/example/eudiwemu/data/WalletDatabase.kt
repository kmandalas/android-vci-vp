package com.example.eudiwemu.data

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.eudiwemu.data.dao.TransactionLogDao
import com.example.eudiwemu.data.entity.TransactionLogEntry

@Database(entities = [TransactionLogEntry::class], version = 1, exportSchema = false)
abstract class WalletDatabase : RoomDatabase() {
    abstract fun transactionLogDao(): TransactionLogDao
}
