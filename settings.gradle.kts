// dependencyResolutionManagement's repositoriesMode/RepositoriesMode/
// FAIL_ON_PROJECT_REPOS below are all part of Gradle's centralized
// repository declaration feature, still marked @Incubating -- meaning
// they work correctly today, but the API surface could change in a
// future Gradle release, not that anything here is broken. Same
// suppression already applied to both projects' app/build.gradle.kts
// for the equivalent AGP Variant API warnings.
@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Required for com.github.nextcloud:Android-SingleSignOn and
        // com.github.stefan-niedermann.nextcloud-commons:sso-glide, which
        // are built directly from their GitHub repos/tags via JitPack, not
        // published to Maven Central -- confirmed via both projects' own
        // README instructions.
        maven(url = "https://jitpack.io")
    }
}

rootProject.name = "Kitchen Archive Tool"
include(":app")
