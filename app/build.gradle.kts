plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)

    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.0"
}

// Read LOCAL_IP from gradle.properties (defaults to localhost if not set)
val localIp: String = project.findProperty("LOCAL_IP")?.toString() ?: "localhost"

android {
    namespace = "com.example.eudiwemu"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.eudiwemu"
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
            buildConfigField("String", "AUTH_SERVER_HOST", "\"${localIp}:9000\"")
            buildConfigField("String", "AUTH_SERVER_TOKEN_URL", "\"http://${localIp}:9000/oauth2/token\"")
            buildConfigField("String", "ISSUER_URL", "\"http://${localIp}:8080\"")
            buildConfigField("String", "WALLET_PROVIDER_URL", "\"http://${localIp}:9001/wp\"")

            // Uses render.com backend
//            buildConfigField("String", "AUTH_SERVER_HOST", "\"vc-auth-server.onrender.com\"")
//            buildConfigField("String", "AUTH_SERVER_TOKEN_URL", "\"https://vc-auth-server.onrender.com/oauth2/token\"")
//            buildConfigField("String", "ISSUER_URL", "\"https://vc-issuer.onrender.com\"")
//            buildConfigField("String", "WALLET_PROVIDER_URL", "\"https://wallet-provider.onrender.com/wp\"")
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
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
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
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // Networking
    implementation("io.ktor:ktor-client-core:2.3.0")
    implementation("io.ktor:ktor-client-cio:2.3.0")
    implementation("io.ktor:ktor-client-json:2.3.0")
    implementation("io.ktor:ktor-client-serialization:2.3.0")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.0")
    implementation("io.ktor:ktor-client-android:2.3.0")
    implementation("io.ktor:ktor-client-logging:2.3.0")

    // JSON Processing
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.0")

    // Security & Cryptography
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.nimbusds:nimbus-jose-jwt:9.37")
    implementation ("androidx.biometric:biometric:1.4.0-alpha02")

    // Authlete SD-JWT
    implementation("com.authlete:sd-jwt:1.5")

    //QR Scanning
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // Other
    implementation ("io.insert-koin:koin-android:3.5.0")
    implementation ("androidx.navigation:navigation-compose:2.4.0-alpha10")
    implementation("io.coil-kt:coil-compose:2.7.0")
}