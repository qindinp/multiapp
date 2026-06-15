pluginManagement {
    repositories {
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

// Feature modules
include(":feature:launcher")
include(":feature:appmanager")
include(":feature:settings")

// Test fixtures
include(":test-fixtures:minimal-app")
