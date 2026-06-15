// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
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
