plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.multiapp.tools.rncapture.pure"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.multiapp.tools.rncapture.pure"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0-pure"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += "lib/*/libshadowhook.so"
            pickFirsts += "lib/*/liblsplant.so"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core:hook"))
    compileOnly(project(":tools:xposed-api-stub"))
}
