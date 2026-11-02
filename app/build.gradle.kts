plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.picca.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.picca.launcher"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.2.2"
        ndk { abiFilters += listOf("arm64-v8a") } // the bundled ngrok agent is arm64 only
    }

    // A fixed debug key committed to the repo so every CI build is signed identically and
    // each new APK installs as an *update* (same package + same signature). Without this,
    // every GitHub Actions runner would mint its own throwaway debug key and Android would
    // refuse the next APK with "App not installed". Debug-only; password is the stock "android".
    signingConfigs {
        getByName("debug") {
            storeFile = file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties", "META-INF/DEPENDENCIES")
        // The ngrok agent ships as src/main/jniLibs/arm64-v8a/libngrok.so (placed there by CI).
        // Legacy packaging extracts it to nativeLibraryDir at install time, which is the one
        // place a non-root app may exec a binary from on Android 10+.
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
}
