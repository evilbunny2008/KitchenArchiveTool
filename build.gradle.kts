// Top-level build file -- the app module's build.gradle.kts applies
// specific plugins; this only declares them (apply false) so version
// numbers are resolved once, from the version catalogue, rather than
// duplicated per module.
//
// No separate Kotlin Android plugin declared here -- AGP 9's built-in
// Kotlin support (gradle.properties: android.builtInKotlin, must NOT be
// set to false) compiles the Kotlin sources directly, so only
// kotlin.compose (Compose compiler) and kotlin.serialization (needed for
// the @Serializable JSON model classes) are applied as separate compiler
// plugins. ksp is required for Room's annotation processing.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
