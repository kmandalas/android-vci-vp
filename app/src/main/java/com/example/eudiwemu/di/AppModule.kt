package com.example.eudiwemu.di

import com.example.eudiwemu.security.WalletKeyManager
import com.example.eudiwemu.service.IssuanceService
import com.example.eudiwemu.service.VpTokenService
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.dsl.module

val appModule = module {
    // Provide HttpClient as a singleton
    single {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }

    // Provide WalletKeyManager as a singleton
    single { WalletKeyManager() }

    // Define single instances of your services
    // Inject dependencies into services
    single { IssuanceService(get(), get()) }
    single { VpTokenService(get(), get()) }
}