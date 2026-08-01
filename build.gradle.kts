// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.detekt) apply false

}

// Workaround for KSP2 multi-round duplicate class issue with Hilt
subprojects {
    afterEvaluate {
        tasks.withType<JavaCompile>().configureEach {
            exclude("**/byRounds/**")
        }
    }
}

// Configure JUnit 5 for all subprojects
subprojects {
    afterEvaluate {
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }
}

// JaCoCo code coverage
subprojects {
    afterEvaluate {
        if (plugins.hasPlugin("com.android.application") || plugins.hasPlugin("com.android.library")) {
            apply(plugin = "jacoco")

            extensions.configure<JacocoPluginExtension> {
                toolVersion = "0.8.12"
            }

            // 仅定义了 runtime flavor 的模块（当前只有 :app）使用 hosted 变体名；
            // 无 flavor 的 application（如 test-fixtures）与 library 保持 debug 命名。
            val appExt = extensions.findByName("android") as? com.android.build.api.dsl.ApplicationExtension
            val hasRuntimeFlavor = appExt?.productFlavors?.any { it.dimension == "runtime" } == true
            val testTaskName = if (hasRuntimeFlavor) "testHostedDebugUnitTest" else "testDebugUnitTest"
            val classesVariant = if (hasRuntimeFlavor) "hostedDebug" else "debug"

            tasks.register<JacocoReport>("jacocoTestReport") {
                dependsOn(testTaskName)

                group = "verification"
                description = "Generate JaCoCo coverage report for debug variant"

                // exec 文件固定位于 build/jacoco/<taskName>.exec，直接指向具体目录
                val execFiles = fileTree(layout.buildDirectory.dir("jacoco")) {
                    include("$testTaskName.exec")
                }

                // base 收窄到具体编译输出目录，避免扫描整个 build 根触发隐式依赖检查
                val classFiles = fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/$classesVariant")) {
                    exclude(
                        "**/R.class",
                        "**/R\$*.class",
                        "**/BuildConfig.class",
                        "**/Manifest*.*",
                        "**/*_MembersInjector.class",
                        "**/Dagger*Component*.*",
                        "**/*Module_*Factory.class",
                        "**/*_Factory.class",
                        "**/Hilt_*.*",
                        "**/*GeneratedInjector.*"
                    )
                } + fileTree(layout.buildDirectory.dir("intermediates/javac/$classesVariant/classes")) {
                    exclude(
                        "**/R.class",
                        "**/R\$*.class",
                        "**/BuildConfig.class",
                        "**/Manifest*.*",
                        "**/*_MembersInjector.class",
                        "**/Dagger*Component*.*",
                        "**/*Module_*Factory.class",
                        "**/*_Factory.class",
                        "**/Hilt_*.*",
                        "**/*GeneratedInjector.*"
                    )
                }

                sourceDirectories.setFrom(files("$projectDir/src/main/java", "$projectDir/src/main/kotlin"))
                classDirectories.setFrom(classFiles)
                executionData.setFrom(execFiles)

                reports {
                    xml.required.set(true)
                    html.required.set(true)
                }
            }
        }
    }
}

// Configure Detekt for all subprojects
subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")
    configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        allRules = false
        config.setFrom("$rootDir/config/detekt/detekt.yml")
        source.setFrom(files("src/main/java", "src/main/kotlin"))
    }
    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        jvmTarget = "17"
    }
}
