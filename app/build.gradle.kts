plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ===== ATNAUJINIMAS: pakeisk šią versiją, kai YouTube "sulūžta" =====
// Naujausia versija: https://github.com/TeamNewPipe/NewPipeExtractor/releases
val newPipeExtractorVersion = "v0.27.0"

android {
    namespace = "lt.faineyr.s3remote"
    compileSdk = 35
    defaultConfig {
        applicationId = "lt.faineyr.s3remote"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.2.1"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            // Pasirašoma debug raktu – kad APK būtų galima įdiegti be papildomų nustatymų
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources { excludes += setOf("META-INF/*") } }
}

dependencies {
    implementation("com.github.TeamNewPipe:NewPipeExtractor:$newPipeExtractorVersion")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
}
