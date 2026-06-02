plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.app.vocalmaster"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.app.vocalmaster"
        minSdk = 26          // Android 8.0 — TarsosDSP AudioDispatcher 요구사항
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // AndroidX 기본
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.fragment:fragment-ktx:1.8.0")
    implementation("androidx.activity:activity-ktx:1.9.0")

    // Media3 ExoPlayer — 반주 재생 및 Pitch Shifting
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("androidx.media3:media3-common:1.3.1")

    // TarsosDSP Android — 실시간 마이크 피치 분석
    // 공식 0110.be 의 Android jar를 app/libs/ 에 직접 넣어 사용.
    // (Maven/JitPack 자동 변환 시 AAR 매니페스트 문제로 빌드 실패하므로 jar 직접 포함)
    // 다운로드: https://0110.be/releases/TarsosDSP/TarsosDSP-latest/TarsosDSP-Android-latest.jar
    // 주의: AudioDispatcherFactory 는 be.tarsos.dsp.io.jvm 패키지에 있음
    implementation(files("libs/TarsosDSP-Android-latest.jar"))

    // Kotlin Coroutines — 백그라운드 IO (압축 해제, 파일 스캔)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Gson — pitch.json 파싱
    implementation("com.google.code.gson:gson:2.10.1")
}
