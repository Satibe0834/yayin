plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// GitHub Actions her derlemede artan bir numara verir; uygulama içi güncelleme bununla karşılaştırır
val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
// Güncellemeler ve aile mesajları: herkese açık dağıtım deposu (kod deposu gizli olabilir)
val updateRepo = System.getenv("UPDATE_REPO")?.ifBlank { null } ?: System.getenv("GITHUB_REPOSITORY") ?: ""
val releaseKeystore = file("release.keystore")

android {
    namespace = "com.yayin.player"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yayin.player"
        minSdk = 23          // Android 6.0 ve üzeri (çoğu TV kutusu dahil)
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.$runNumber"
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
        // Yalnızca ARM cihazlar (Fire TV, Android TV kutuları): VLC ile APK boyutu yarıya iner
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    // Sabit imza: her sürüm aynı anahtarla imzalanır, böylece eskisinin üzerine kurulabilir
    signingConfigs {
        create("release") {
            if (releaseKeystore.exists()) {
                storeFile = releaseKeystore
                storePassword = "yayin-imza"
                keyAlias = "yayin"
                keyPassword = "yayin-imza"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseKeystore.exists()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        // FFmpeg çözücü kütüphanesi bunu gerektiriyor
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    // Derleme sonundaki kod denetimi (lint) uyarıları derlemeyi durdurmasın
    lint { checkReleaseBuilds = false; abortOnError = false }
    // VLC ve FFmpeg aynı C++ kütüphanesini içeriyor: birini al
    packaging { jniLibs { pickFirsts += listOf("**/libc++_shared.so") } }
}

val media3 = "1.3.1"

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")

    // Yerel video motoru (ExoPlayer) – HLS, DASH, MPEG-TS, MP4, MKV
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")

    // FFmpeg ses çözücü (yalnızca ses; cihazın çözemediği MP2/AC3/DTS vb. için).
    // Bkz. YayinRenderers.kt – deneysel video çözücüsü bilerek kullanılmaz.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:$media3+1")

    // Film/dizi için VLC motoru: standart dışı yazılmış dosyaları da açar (varsayılan film/dizi oynatıcısı)
    implementation("org.videolan.android:libvlc-all:3.5.1")
    // VLC eski bir Fragment sürümü getiriyor; izin istekleri (registerForActivityResult) için yenisi şart
    implementation("androidx.fragment:fragment-ktx:1.8.2")

    // Telefonla kurulum: yerel web sunucusu + QR kod
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.3")

    // Google TV ana ekranı "İzlemeye devam et"
    implementation("androidx.tvprovider:tvprovider:1.0.0")
}
