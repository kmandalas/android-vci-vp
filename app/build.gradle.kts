import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.googleServices)
}

// Read LOCAL_IP from gradle.properties (defaults to localhost if not set)
val localIp: String = project.findProperty("LOCAL_IP")?.toString() ?: "localhost"

// Read local.properties for secrets kept out of version control
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}
val appCheckDebugToken: String = localProperties.getProperty("APP_CHECK_DEBUG_TOKEN", "")

android {
    namespace = "dev.kmandalas.wallet"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.kmandalas.wallet"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }
    buildTypes {
        debug {
            isMinifyEnabled = false
            // Uses LOCAL_IP from gradle.properties
//            buildConfigField("String", "AUTH_SERVER_HOST", "\"${localIp}:9000\"")
//            buildConfigField("String", "AUTH_SERVER_TOKEN_URL", "\"http://${localIp}:9000/oauth2/token\"")
//            buildConfigField("String", "ISSUER_URL", "\"http://${localIp}:8080\"")
//            buildConfigField("String", "WALLET_PROVIDER_URL", "\"http://${localIp}:9001/wp\"")

            buildConfigField("String", "APP_CHECK_DEBUG_TOKEN", "\"$appCheckDebugToken\"")
            // Debug: placeholder — signing cert hash validation is disabled when isProd=false
            buildConfigField("String", "SIGNING_CERT_HASH", "\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\"")

            // Uses render.com backend
            buildConfigField("String", "AUTH_SERVER_HOST", "\"vc-auth-server.onrender.com\"")
            buildConfigField("String", "AUTH_SERVER_TOKEN_URL", "\"https://vc-auth-server.onrender.com/oauth2/token\"")
            buildConfigField("String", "ISSUER_URL", "\"https://vc-issuer.onrender.com\"")
            buildConfigField("String", "WALLET_PROVIDER_URL", "\"https://wallet-provider.onrender.com/wp\"")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "AUTH_SERVER_HOST", "\"vc-auth-server.kmandalas.com\"")
            buildConfigField("String", "AUTH_SERVER_TOKEN_URL", "\"https://vc-auth-server.kmandalas.com/oauth2/token\"")
            buildConfigField("String", "ISSUER_URL", "\"https://vc-issuer.kmandalas.com\"")
            buildConfigField("String", "WALLET_PROVIDER_URL", "\"https://wallet-provider.kmandalas.com/wp\"")
            // TODO: Replace with actual release signing cert SHA-256 (base64) from ./gradlew signingReport
            buildConfigField("String", "SIGNING_CERT_HASH", "\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // Networking
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.json)
    implementation(libs.ktor.client.serialization)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.logging)

    // JSON Processing
    implementation(libs.kotlinx.serialization.json)

    // Security & Cryptography
    implementation(libs.androidx.security.crypto)
    implementation(libs.nimbus.jose.jwt)
    implementation(libs.androidx.biometric)

    // Authlete SD-JWT
    implementation(libs.authlete.sd.jwt)

    // Authlete CBOR (for mDoc support)
    implementation(libs.authlete.cbor)

    // Multipaz (ISO 18013-5 proximity presentation transport)
    implementation(libs.multipaz) {
        exclude(group = "org.bouncycastle")
    }
    implementation(libs.multipaz.android.legacy) {
        exclude(group = "org.bouncycastle")
    }

    // ZXing (QR code generation for proximity presentation)
    implementation(libs.zxing.core)

    // QR Scanning
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)

    // DI
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Image Loading
    implementation(libs.coil.compose)

    // Room Database
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Firebase App Check
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.appcheck.playintegrity)
    debugImplementation(libs.firebase.appcheck.debug)

    // Runtime Application Self-Protection (freeRASP)
    implementation(libs.freerasp)
}
