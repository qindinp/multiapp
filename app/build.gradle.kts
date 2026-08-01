plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.multiapp.app"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.multiapp.app"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0-alpha01"

        testInstrumentationRunner = "com.multiapp.app.TestRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // 临时基线签名：正式发布签名密钥 W5 阶段到位后替换（见 maturity-execution-plan）
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    // D1 决策（2026-08-01 确认）：hosted 为唯一商业发布变体；
    // legacy 保留 Stub/loader.dex/Xposed 实验路径，物理隔离，不进入发布渠道。
    flavorDimensions += "runtime"
    productFlavors {
        create("hosted") {
            dimension = "runtime"
            isDefault = true
        }
        create("legacy") {
            dimension = "runtime"
        }
    }

    sourceSets {
        getByName("legacy") {
            // 静态相对路径（AGP 不接受 Provider）；任务依赖由 merge*Assets 保证顺序
            assets.srcDirs("build/generated/assets/legacy")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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

// loader.dex 仅属于 legacy 变体（D1 决策）：hosted 不复制、不打包。
// 输出到 build/generated（不再写源码目录），声明 inputs/outputs 保证可复现。
val copyLoaderDex = tasks.register<Copy>("copyLoaderDex") {
    dependsOn(":core:stub:generateLoaderDex")
    val loaderDexSrc = file("${project(":core:stub").projectDir}/src/main/assets/loader.dex")
    inputs.file(loaderDexSrc)
    val outDir = layout.buildDirectory.dir("generated/assets/legacy").get().asFile
    outputs.dir(outDir)
    from(loaderDexSrc)
    into(outDir)
    rename { "loader.dex" }
}

tasks.configureEach {
    if (name == "mergeLegacyDebugAssets" || name == "mergeLegacyReleaseAssets") {
        dependsOn(copyLoaderDex)
    }
}

val verifyEngineBoundary = tasks.register("verifyEngineBoundary") {
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
    implementation(project(":core:manifest"))
    implementation(project(":core:instance"))
    implementation(project(":core:installer"))

    // Legacy 实验路径（Stub APK 构建器、loader.dex、Xposed API）：仅 legacy 变体（D1 决策）
    // flavor 专属 configuration 在 Kotlin DSL 中需用字符串名引用
    "legacyImplementation"(project(":core:stub"))
    "legacyImplementation"(project(":core:xposed"))

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
    testRuntimeOnly(libs.junit5.launcher)
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
