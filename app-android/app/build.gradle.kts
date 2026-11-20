import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lentesinteligentes.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lentesinteligentes.app"
        minSdk = 26          // Android 8 o mas nuevo
        targetSdk = 35       // Android 15 (el del Samsung A36)
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Una sola libreria externa: OkHttp, para el WebSocket seguro con Gemini Live.
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
