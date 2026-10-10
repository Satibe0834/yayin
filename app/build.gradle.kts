plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// GitHub Actions her derlemede artan bir numara verir; uygulama içi güncelleme bununla karşılaştırır
val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
val updateRepo = System.getenv("GITHUB_REPOSITORY") ?: ""
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

    // Telefonla kurulum: yerel web sunucusu + QR kod
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.3")

    // Google TV ana ekranı "İzlemeye devam et"
    implementation("androidx.tvprovider:tvprovider:1.0.0")
}
