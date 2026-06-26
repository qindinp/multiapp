plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.multiapp.core.hook"
    compileSdk = 36
    ndkVersion = "29.0.13599879"

    defaultConfig {
        minSdk = 28
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17", "-fvisibility=hidden")
                arguments("-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
        }
    }

    buildFeatures {
        prefab = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildTypes {
        release {
            consumerProguardFiles("proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))

    implementation(libs.core.ktx)
    implementation(libs.timber)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    implementation(libs.hiddenapibypass)
    implementation(libs.lsplant)
    implementation(libs.shadowhook)
    implementation(libs.dexlib2)

    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.robolectric)
}

tasks.withType<Test> {
    useJUnitPlatform()
    systemProperty("robolectric.dependency.repo.url", "https://maven.aliyun.com/repository/central")
}
