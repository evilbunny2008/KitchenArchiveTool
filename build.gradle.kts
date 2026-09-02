// Top-level build file -- the app module's build.gradle.kts applies
// specific plugins; this only declares them (apply false) so version
// numbers are resolved once, from the version catalogue, rather than
// duplicated per module.
//
// UPDATE: switched from AGP 9's built-in Kotlin support back to the
// traditional org.jetbrains.kotlin.android (KGP) plugin for this module --
// testing a hypothesis that Data Binding's Kotlin @BindingAdapter
// resolution (used by BindingUtils.kt) needs KGP's Java-stub generation,
// which built-in Kotlin may not yet fully replicate. kotlin.serialization
// (needed for the @Serializable JSON model classes) and
// androidx.navigation.safeargs.kotlin (generates the *Args/*Directions
// classes from res/navigation/navigation.xml) are also applied as separate
// plugins. ksp is required for Room's annotation processing; unaffected by
// built-in Kotlin either way.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.androidx.navigation.safeargs) apply false
}
