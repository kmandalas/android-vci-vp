package com.example.eudiwemu.di

import android.util.Log
import com.example.eudiwemu.BuildConfig
import com.example.eudiwemu.security.WalletKeyManager
import com.example.eudiwemu.service.IssuanceService
import com.example.eudiwemu.service.VpTokenService
import com.example.eudiwemu.service.WiaService
import com.example.eudiwemu.service.WuaService
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val appModule = module {
    // Provide HttpClient as a singleton
    single {
        HttpClient(Android) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                })
            }
            install(Logging) {
                logger = object : Logger {
                    override fun log(message: String) {
                        Log.d("Ktor", message)
                    }
                }
                level = if (BuildConfig.DEBUG) LogLevel.ALL else LogLevel.NONE
            }
        }
    }

    // Provide WalletKeyManager as a singleton
    single { WalletKeyManager() }

    // Define single instances of your services
    single { WiaService(get(), get(), androidContext()) }
    single { IssuanceService(get(), get(), get(), androidContext()) }
    single { VpTokenService(get(), get()) }
    single { WuaService(get(), get(), androidContext()) }
}
