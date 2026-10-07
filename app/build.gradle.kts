plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.s1ambient"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.s1ambient"
        minSdk = 28
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
