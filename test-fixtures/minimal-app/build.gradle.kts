plugins {
    id("com.android.application")
}

android {
    namespace = "com.test.minimal"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.test.minimal"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // 零外部依赖 — 纯 Android framework
}
