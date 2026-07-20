plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// ─── loader DEX 生成 ───────────────────────────────────────────────

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:manifest"))
    implementation(project(":core:apk"))
    implementation(project(":core:hook"))

    implementation(libs.core.ktx)
    implementation(libs.timber)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.gson)
    implementation(libs.apksig)
    implementation(libs.apkparser)

    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.launcher)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlin.test)
}

android {
    namespace = "com.multiapp.core.stub"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    defaultConfig { minSdk = 28 }
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
}

// 声明一个 configuration 来获取 :core:loader 的运行时 classpath
val loaderRuntimeFiles = configurations.create("loaderRuntimeFiles") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    // 这些是 :core:loader 的外部依赖，用于 loader.dex 编译
    add(loaderRuntimeFiles.name, libs.gson)
    add(loaderRuntimeFiles.name, libs.apksig)
    add(loaderRuntimeFiles.name, libs.apkparser)
    add(loaderRuntimeFiles.name, libs.timber)
    add(loaderRuntimeFiles.name, libs.coroutines.core)
    add(loaderRuntimeFiles.name, libs.coroutines.android)
    add(loaderRuntimeFiles.name, libs.hiddenapibypass)
    add(loaderRuntimeFiles.name, libs.shadowhook)
}

val generateLoaderDex = tasks.register("generateLoaderDex") {
    description = "Compile :core:loader classes into a DEX for StubBuilder"
    group = "build"

    val assetsDir = file("src/main/assets")
    val dexFile = File(assetsDir, "loader.dex")

    dependsOn(":core:model:assembleDebug")
    dependsOn(":core:common:assembleDebug")
    dependsOn(":core:hook:assembleDebug")
    dependsOn(":core:identity:assembleDebug")
    dependsOn(":core:manifest:assembleDebug")
    dependsOn(":core:loader:assembleDebug")

    doLast {
        assetsDir.mkdirs()
        val stagingDir = File(temporaryDir, "staging")
        stagingDir.mkdirs()

        // 1. 收集所有 core 模块的编译类文件
        // AGP 8.x 把类输出从 classes/debug 改到了 runtime_library_classes_dir/debug
        val coreModules = listOf("model", "common", "hook", "identity", "manifest", "loader")
        for (mod in coreModules) {
            // 尝试多个可能的类输出目录
            val candidates = listOf(
                rootProject.file("core/$mod/build/intermediates/classes/debug"),
                rootProject.file("core/$mod/build/intermediates/runtime_library_classes_dir/debug"),
                rootProject.file("core/$mod/build/tmp/kotlin-classes/debug")
            )
            val classesDir = candidates.firstOrNull { it.isDirectory }
            if (classesDir != null) {
                classesDir.copyRecursively(stagingDir, overwrite = true)
            } else {
                logger.warn("generateLoaderDex: no classes dir found for core/$mod")
            }
        }

        // 2. 收集外部依赖 (通过 configuration 获取 JAR/AAR)
        val depsDir = File(temporaryDir, "deps")
        depsDir.mkdirs()
        var depClassCount = 0
        try {
            val files = loaderRuntimeFiles.resolvedConfiguration.resolvedArtifacts
            for (artifact in files) {
                val f = artifact.file
                logger.lifecycle("generateLoaderDex: processing dep ${f.name} (${f.length()} bytes)")
                when (f.extension) {
                    "jar" -> {
                        copy { from(zipTree(f)); into(stagingDir); exclude("META-INF/**") }
                    }
                    "aar" -> {
                        copy {
                            from(zipTree(f)) { include("classes.jar") }
                            into(depsDir)
                        }
                        val aarJar = File(depsDir, "classes.jar")
                        if (aarJar.exists()) {
                            copy { from(zipTree(aarJar)); into(stagingDir); exclude("META-INF/**") }
                            aarJar.delete()
                        }
                    }
                }
            }
            depClassCount = stagingDir.walk().filter { it.extension == "class" }.count() - coreModules.sumOf { mod ->
                // 尝试多个可能的类输出目录
                val candidates = listOf(
                    rootProject.file("core/$mod/build/intermediates/classes/debug"),
                    rootProject.file("core/$mod/build/intermediates/runtime_library_classes_dir/debug"),
                    rootProject.file("core/$mod/build/tmp/kotlin-classes/debug")
                )
                val dir = candidates.firstOrNull { it.isDirectory }
                if (dir != null) dir.walk().filter { it.extension == "class" }.count() else 0
            }
            logger.lifecycle("generateLoaderDex: resolved ${files.size} dependency artifacts, ~$depClassCount dep classes")
        } catch (e: Exception) {
            logger.warn("generateLoaderDex: failed to resolve deps: ${e.message}")
            e.printStackTrace()
        }
        // depsDir.deleteRecursively()  // keep for debugging

        // 3. 查找 d8
        val sdkDir = rootProject.findProperty("sdk.dir")?.toString()
            ?.replace("\\:", ":")
            ?: System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: run {
                val lp = rootProject.file("local.properties")
                if (lp.exists()) {
                    lp.readLines()
                        .firstOrNull { it.startsWith("sdk.dir=") }
                        ?.substringAfter("sdk.dir=")
                        ?.replace("\\:", ":")
                } else null
            }
            ?: error("Android SDK not found")

        val d8Exe = if (System.getProperty("os.name").lowercase().contains("windows"))
            "d8.bat" else "d8"
        val d8Path = File(sdkDir, "build-tools/37.0.0/$d8Exe").absolutePath
        check(File(d8Path).exists()) { "d8 not found at $d8Path" }

        // 4. 用 d8 一步编译所有 class 为 DEX
        val classFiles = stagingDir.walk()
            .filter { it.extension == "class" }
            .map { it.absolutePath }
            .toList()
        logger.lifecycle("generateLoaderDex: compiling ${classFiles.size} class files")
        val argFile = File(temporaryDir, "classes.txt")
        argFile.writeText(classFiles.joinToString("\n"))

        providers.exec {
            commandLine(d8Path, "--min-api", "28", "--output", assetsDir.absolutePath, "@${argFile.absolutePath}")
        }.result.get().assertNormalExitValue()
        argFile.delete()
        stagingDir.deleteRecursively()

        // 重命名 classes.dex → loader.dex
        val d8Output = File(assetsDir, "classes.dex")
        check(d8Output.exists()) { "d8 failed to produce classes.dex" }
        dexFile.delete()  // Windows: renameTo fails if target exists
        d8Output.renameTo(dexFile)
        check(dexFile.exists()) { "Failed to rename classes.dex to loader.dex" }
        logger.lifecycle("generateLoaderDex: ${dexFile.absolutePath} (${dexFile.length()} bytes)")
    }
}

// 每次编译前自动生成 loader.dex
// 必须在 mergeDebugAssets 之前完成，否则 assets 不会被合并到 APK
tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("Assets")) {
        dependsOn(generateLoaderDex)
    }
    if (name.startsWith("assemble")) {
        dependsOn(generateLoaderDex)
    }
}
