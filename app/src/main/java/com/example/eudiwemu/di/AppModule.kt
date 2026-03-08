package com.example.eudiwemu.di

import android.util.Log
import androidx.room.Room
import com.example.eudiwemu.BuildConfig
import com.example.eudiwemu.data.WalletDatabase
import com.example.eudiwemu.security.WalletKeyManager
import com.example.eudiwemu.service.ExportImportService
import com.example.eudiwemu.service.IssuanceService
import com.example.eudiwemu.service.VpTokenService
import com.example.eudiwemu.service.WiaService
import com.example.eudiwemu.service.WuaService
import com.example.eudiwemu.config.AppConfig
import com.example.eudiwemu.security.AppCheckTokenProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.plugin
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import com.example.eudiwemu.ui.viewmodel.WalletViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
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
            HttpResponseValidator {
                validateResponse { response ->
                    val status = response.status.value
                    if (status >= 400) {
                        val url = response.call.request.url.toString()
                        val body = try { response.bodyAsText() } catch (_: Exception) { "" }
                        Log.w("Ktor", "HTTP $status from $url — $body")
                        val origin = when {
                            url.contains(AppConfig.WALLET_PROVIDER_URL) -> "Wallet Provider"
                            url.contains(AppConfig.ISSUER_URL) -> "Credential Issuer"
                            url.contains(AppConfig.AUTH_SERVER_HOST) -> "Auth Server"
                            else -> "Server"
                        }
                        throw Exception("$origin error (HTTP $status)")
                    }
                }
            }
        }.also { client ->
            client.plugin(HttpSend).intercept { request ->
                val url = request.url.toString()
                val isWalletProvider = url.startsWith(AppConfig.WALLET_PROVIDER_URL)
                val isPublicPath = url.contains("/.well-known/") || url.contains("/wua/status/")
                if (isWalletProvider && !isPublicPath) {
                    val token = AppCheckTokenProvider.getToken()
                    if (token != null) {
                        request.headers.append("X-Firebase-AppCheck", token)
                    } else {
                        Log.w("Ktor", "App Check token unavailable for wallet provider request")
                    }
                }
                execute(request)
            }
        }
    }

    // Provide WalletKeyManager as a singleton
    single { WalletKeyManager() }

    // Room Database
    single {
        Room.databaseBuilder(
            androidContext(),
            WalletDatabase::class.java,
            "wallet_database"
        ).build()
    }
    single { get<WalletDatabase>().transactionLogDao() }

    // Define single instances of your services
    single { WiaService(get(), get(), androidContext()) }
    single { IssuanceService(get(), get(), androidContext(), get()) }
    single { VpTokenService(get(), get()) }
    single { WuaService(get(), get(), androidContext()) }
    single { ExportImportService(get()) }

    viewModel { WalletViewModel(get(), get(), get(), get(), get(), get()) }
}
