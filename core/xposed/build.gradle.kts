plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.multiapp.core.xposed"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        minSdk = 28
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
}

dependencies {
    implementation(project(":core:hook"))
    implementation(project(":core:common"))

    implementation(libs.core.ktx)
    implementation(libs.timber)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.launcher)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.robolectric)
}

tasks.withType<Test> {
    useJUnitPlatform()
    systemProperty("robolectric.dependency.repo.url", "https://maven.aliyun.com/repository/central")
}
