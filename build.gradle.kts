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

            tasks.register<JacocoReport>("jacocoTestReport") {
                dependsOn("testDebugUnitTest")

                group = "verification"
                description = "Generate JaCoCo coverage report for debug variant"

                val execFiles = fileTree(layout.buildDirectory) {
                    include("**/testDebugUnitTest.exec")
                }

                val classFiles = fileTree(layout.buildDirectory) {
                    include(
                        "tmp/kotlin-classes/debug/**/*.class",
                        "intermediates/javac/debug/**/*.class"
                    )
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
