pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    @Suppress("UnstableApiUsage")
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
    }
}

rootProject.name = "MultiApp"

// App module
include(":app")

// Core modules
include(":core:model")
include(":core:common")
include(":core:engine")
include(":core:designsystem")
include(":core:apk")
include(":core:hook")
include(":core:manifest")
include(":core:identity")
include(":core:loader")
include(":core:stub")
include(":core:instance")
include(":core:installer")
include(":core:workprofile")
include(":core:xposed")

// Tool modules
include(":tools:xposed-api-stub")
include(":tools:lsposed-rn-capture")
include(":tools:lsposed-rn-capture-pure")

// Feature modules
include(":feature:launcher")
include(":feature:appmanager")
include(":feature:settings")

// Test fixtures
include(":test-fixtures:minimal-app")
