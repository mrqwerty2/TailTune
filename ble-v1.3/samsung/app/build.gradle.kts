plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.tailtune.standalone"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.tailtune.standalone"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.3-nav"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
