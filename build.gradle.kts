// Top-level build file -- the app module's build.gradle.kts applies
// specific plugins; this only declares them (apply false) so version
// numbers are resolved once, from the version catalogue, rather than
// duplicated per module.
//
// No separate Kotlin Android plugin declared here -- AGP 9's built-in
// Kotlin support (gradle.properties: android.builtInKotlin, must NOT be
// set to false) compiles the Kotlin sources directly, so only
// kotlin.serialization (needed for the @Serializable JSON model classes)
// and androidx.navigation.safeargs.kotlin (generates the *Args/*Directions
// classes from res/navigation/navigation.xml -- was missing entirely,
// which is why those classes were unresolved) are applied as separate
// plugins. ksp is required for Room's annotation processing.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.androidx.navigation.safeargs) apply false
}
