plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.marsel.wledplayer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.marsel.wledplayer"
        minSdk = 21
        targetSdk = 34
        // На GitHub номер версии подставляется автоматически (см. .github/workflows/release.yml).
        // При сборке на компьютере остаются значения справа от «?:».
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 8
        versionName = System.getenv("VERSION_NAME") ?: "1.9"
    }

    // Подпись релиза. На GitHub ключ приходит из секретов; на компьютере этот блок не используется.
    val keystorePath = System.getenv("KEYSTORE_PATH")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.preference:preference-ktx:1.2.1")

    // Библиотека Material для стилей
    implementation("com.google.android.material:material:1.12.0")

    // Плеер Media3
    val media3Version = "1.2.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-dash:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:${media3Version}")


    // Сервер Ktor (для связи с пультом)
    val ktorVersion = "2.3.9"
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")
}