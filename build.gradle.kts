// Top-level build file -- the app module's build.gradle.kts applies
// specific plugins; this only declares them (apply false) so version
// numbers are resolved once, from the version catalogue, rather than
// duplicated per module.
//
// No separate Kotlin Android plugin declared here -- AGP 9's built-in
// Kotlin support (gradle.properties: android.builtInKotlin, must NOT be
// set to false) compiles the Kotlin sources directly. Previously tried
// applying org.jetbrains.kotlin.android (KGP) instead of legacy-kapt below
// -- REVERTED: that combination is actively incompatible with AGP 9.4.0
// (throws "ApplicationExtensionImpl cannot be cast to BaseExtension").
// legacy-kapt (com.android.legacy-kapt) is Google's own documented,
// AGP-9-compatible fallback for legacy kapt-style annotation processing
// under built-in Kotlin -- being tried here on the hypothesis that Data
// Binding's Kotlin @BindingAdapter resolution (used by BindingUtils.kt)
// still depends on kapt-generated Java stubs of Kotlin code, which has
// been true historically and has no equivalent currently configured.
// kotlin.serialization (needed for the @Serializable JSON model classes)
// and androidx.navigation.safeargs.kotlin (generates the *Args/*Directions
// classes from res/navigation/navigation.xml) are applied as separate
// plugins. ksp is required for Room's annotation processing.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.legacy.kapt) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.androidx.navigation.safeargs) apply false
}
