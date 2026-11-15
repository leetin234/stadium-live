import java.util.Properties

plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "1.9.24"
}

// release 서명: 저장소 밖 ~/.android/stadiumlive-release.properties가 있으면 그 키로, 없으면 디버그 키로 서명한다.
val releaseProps = Properties().apply {
    val f = File(System.getProperty("user.home"), ".android/stadiumlive-release.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    useLibrary("android.car", false)
    namespace = "kr.stadiumlive"
    compileSdk = 34
    defaultConfig {
        applicationId = "kr.stadiumlive.vehicle"
        minSdk = 28
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
    }
    signingConfigs {
        create("release") {
            if (releaseProps.isNotEmpty()) {
                storeFile = File(releaseProps.getProperty("storeFile"))
                storePassword = releaseProps.getProperty("storePassword")
                keyAlias = releaseProps.getProperty("keyAlias")
                keyPassword = releaseProps.getProperty("keyPassword")
            } else {
                initWith(getByName("debug"))
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    // 오디오 원본은 압축하지 않고 넣어 SoundPool 로딩 지연을 줄인다.
    androidResources { noCompress += listOf("ogg", "wav", "json") }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.material:material:1.6.8")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    // Pleos Vehicle SDK: 실내 앰비언트 조명·공조 송풍 제어.
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
