plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yayin.player"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yayin.player"
        minSdk = 23          // Android 6.0 ve üzeri (çoğu TV kutusu dahil)
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Kendi imza anahtarınızı oluşturana kadar debug anahtarıyla imzalanır,
            // böylece release APK de doğrudan kurulabilir.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}

val media3 = "1.3.1"

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")

    // Yerel video motoru (ExoPlayer) – HLS, DASH, MPEG-TS, MP4, MKV
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")

    // FFmpeg ses çözücü: AC3, E-AC3, DTS, MP2, TrueHD, Opus, FLAC, ALAC...
    // (Jellyfin projesinin Maven Central'da yayımladığı hazır derleme)
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:$media3+1")

    // Telefonla kurulum: yerel web sunucusu + QR kod
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.3")

    // Google TV ana ekranı "İzlemeye devam et"
    implementation("androidx.tvprovider:tvprovider:1.0.0")
}
