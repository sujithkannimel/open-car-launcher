plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.minimal.carcompanion"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.minimal.carcompanion"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("../app/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }

    // Same fuel / trip / battery maths as the head unit launcher.
    sourceSets {
        getByName("main").java.srcDir("../shared/src/main/java")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
