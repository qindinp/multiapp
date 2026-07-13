plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.multiapp.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.multiapp.app"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0-alpha01"

        testInstrumentationRunner = "com.multiapp.app.TestRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += "lib/*/libshadowhook.so"
            pickFirsts += "lib/*/liblsplant.so"
        }
        resources {
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/LICENSE-notice.md"
        }
    }
}

ksp {
    arg("correctErrorTypes", "true")
}

// 将 core:stub 生成的 loader.dex 复制到 app 模块的 assets 目录
// AAR 不包含 assets，所以需要显式复制到 app 模块确保打包进 APK
val copyLoaderDex by tasks.registering(Copy::class) {
    // 必须依赖 generateLoaderDex，否则会复制旧版 loader.dex
    dependsOn(":core:stub:generateLoaderDex")
    from("${project(":core:stub").projectDir}/src/main/assets/loader.dex")
    into("${projectDir}/src/main/assets")
    rename { "loader.dex" }
}

tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("Assets")) {
        dependsOn(copyLoaderDex)
    }
}

val verifyEngineBoundary by tasks.registering {
    group = "verification"
    description = "Reject app and feature imports that bypass the virtualization engine facade."
    val sourceRoots = buildList {
        add(project.file("src/main/java"))
        add(project.file("src/main/kotlin"))
        rootProject.subprojects
            .filter { it.path.startsWith(":feature:") }
            .forEach { feature ->
                add(feature.file("src/main/java"))
                add(feature.file("src/main/kotlin"))
            }
    }
    val sources = files(sourceRoots.map { root ->
        fileTree(root) { include("**/*.kt", "**/*.java") }
    })
    inputs.files(sources)
    doLast {
        val forbiddenImport = Regex(
            """^\s*import\s+com\.multiapp\.core\.(loader|hook|xposed)(\.|$)"""
        )
        val violations = sources.files
            .asSequence()
            .filter { it.isFile }
            .flatMap { source ->
                source.readLines().asSequence().mapIndexedNotNull { index, line ->
                    if (forbiddenImport.containsMatchIn(line)) {
                        "${source.relativeTo(rootProject.projectDir)}:${index + 1}: ${line.trim()}"
                    } else {
                        null
                    }
                }
            }
            .sorted()
            .toList()
        check(violations.isEmpty()) {
            "App/feature code must use :core:engine instead of runtime primitives:\n" +
                violations.joinToString("\n")
        }
    }
}

tasks.named("preBuild").configure { dependsOn(verifyEngineBoundary) }

dependencies {
    // All core modules
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:engine"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:apk"))
    implementation(project(":core:manifest"))
    implementation(project(":core:stub"))
    implementation(project(":core:instance"))
    implementation(project(":core:installer"))

    // Feature modules
    implementation(project(":feature:launcher"))
    implementation(project(":feature:appmanager"))
    implementation(project(":feature:settings"))

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.animation)
    implementation(libs.activity.compose)
    implementation(libs.appcompat)
    debugImplementation(libs.compose.ui.tooling)

    // DI + Nav + Lifecycle
    implementation(libs.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)

    // Core
    implementation(libs.timber)
    implementation(libs.core.ktx)
    implementation(libs.security.crypto)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.room.ktx)

    // Testing
    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(project(":core:hook"))
    testImplementation(project(":core:identity"))
    testImplementation(project(":core:loader"))

    // Android Instrumentation Testing
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(project(":core:identity"))
    androidTestImplementation(project(":core:loader"))
}
