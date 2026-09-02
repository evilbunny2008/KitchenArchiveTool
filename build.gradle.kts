// Top-level build file -- the app module's build.gradle.kts applies
// specific plugins; this only declares them (apply false) so version
// numbers are resolved once, from the version catalogue, rather than
// duplicated per module.
//
// No separate Kotlin Android plugin declared here -- the app module only
// applies android.application + kotlin.compose, meaning it's already on
// AGP's built-in Kotlin support path (same migration MX3 Launcher went
// through) rather than the traditional org.jetbrains.kotlin.android
// plugin.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}