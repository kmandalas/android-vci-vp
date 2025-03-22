package com.example.eudiwemu

import android.app.Application
import com.example.eudiwemu.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class WalletApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Start Koin
        startKoin {
            androidLogger()
            androidContext(this@WalletApplication)
            modules(appModule)
        }
    }
}