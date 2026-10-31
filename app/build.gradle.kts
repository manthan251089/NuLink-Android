plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.nulinkicp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.nulinkicp"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.7-hex-validation"
    }

    // Keep Java and Kotlin bytecode targets aligned.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
