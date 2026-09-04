@file:Suppress("UnstableApiUsage")

import com.android.build.api.artifact.SingleArtifact
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.legacy.kapt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.navigation.safeargs)
}

android {
    namespace = "com.odiousapps.kat"
    compileSdk {
        version = release(37)
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
        dataBinding = true
    }

    // Required for F-Droid's reproducible-build verification -- without
    // this, Android's dependency-metadata block gets embedded slightly
    // differently depending on the exact build environment, which makes
    // F-Droid's independently-rebuilt APK fail to match byte-for-byte
    // even when the actual app source is identical.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    defaultConfig {
        applicationId = "com.odiousapps.kat"
        minSdk = 29
        targetSdk = 37
        versionCode = 4000000
        versionName = "4.0.0"
        vectorDrawables.useSupportLibrary = true
        multiDexEnabled = true
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

// Renames the release .aab from AGP's default "app-release.aab" to
// "<appName>-<versionName>.aab", in place, within app/release/ (already
// gitignored) - which turns out to already be this project's actual bundle
// output location (confirmed by an AGP validation error naming that exact
// path as a declared input elsewhere), not a separate custom destination to
// copy into as originally assumed. Critically, this must run *after* AGP's
// own internal "produce...BundleIdeListingFile" task, which declares the
// bundle at its default name as one of its own inputs - renaming (or
// deleting) it any earlier fails that task's input validation with "file
// doesn't exist", which is what happened when this was ordered the other
// way around.
//
// Defined as a proper typed task class (not a closure passed to
// tasks.register) with Provider/Property-typed inputs: the previous version
// captured the whole AGP `variant` object inside a doLast {} closure, and
// resolved variant.artifacts.get(SingleArtifact.BUNDLE) at execution time
// from within that closure. `variant` internally holds live references to
// Project, Configuration, and other Task objects (JavaCompile, etc.) --
// none of which the configuration cache is able to serialize, so every
// build failed to cache with errors naming exactly those types. Declaring
// bundleFile as a RegularFileProperty and wiring it from
// variant.artifacts.get(...) (itself a Provider<RegularFile>) at
// configuration time means only the resolved file path is ever captured --
// the task action itself never touches `variant` at all.
abstract class RenameBundleTask : DefaultTask() {
    @get:InputFile
    abstract val bundleFile: RegularFileProperty

    @get:Input
    abstract val renamedFileName: Property<String>

    @TaskAction
    fun rename() {
        val file = bundleFile.get().asFile
        if (file.exists()) {
            val renamedFile = File(file.parentFile, renamedFileName.get())
            file.copyTo(renamedFile, overwrite = true)
            file.delete()
        } else {
            println("Expected bundle file not found at $file - skipping rename")
        }
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        // NOTE: was "MX3ButtonMapper" (leftover from the sibling project) -- corrected.
        val appName = "kat"
        val versionName = variant.outputs.first().versionName
        val variantNameCapitalized = variant.name.replaceFirstChar { it.uppercase() }
        val ideListingTaskName = "produce${variantNameCapitalized}BundleIdeListingFile"

        // APK variant outputs support a directly settable filename, unlike
        // the bundle (AAB) case above - no separate rename/copy task needed.
        variant.outputs.forEach { output ->
            output.outputFileName.set("$appName-${versionName.get()}.apk")
        }

        val renameBundle = tasks.register("renameBundle$variantNameCapitalized", RenameBundleTask::class.java) {
            group = "build"
            description = "Renames the $variantNameCapitalized .aab in place to $appName-<versionName>.aab"
            mustRunAfter(ideListingTaskName)
            bundleFile.set(variant.artifacts.get(SingleArtifact.BUNDLE))
            renamedFileName.set("$appName-${versionName.get()}.aab")
        }
        // Hooks the rename onto the standard "bundle" task graph, so it also
        // runs automatically from Android Studio's Build > Generate Signed
        // App Bundle flow (which invokes bundleRelease directly), not just
        // when this task is run explicitly by name.
        afterEvaluate {
            tasks.named("bundle$variantNameCapitalized") {
                finalizedBy(renameBundle)
            }
        }
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))

    implementation(libs.jsoup)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.legacy.support.v4)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.legacy.preference.v14)
    // material design and viewpager2
    implementation(libs.material)
    implementation(libs.androidx.viewpager2)
    // Lifecycle dependencies
    implementation(libs.androidx.lifecycle.livedata.ktx)
    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    // room database
    implementation(libs.androidx.room.runtime)
    // optional - Kotlin Extensions and Coroutines support for Room
    implementation(libs.androidx.room.ktx)
    // Room's annotation processor -- was entirely missing before, meaning
    // @Database/@Dao/@Entity classes had no generated implementations
    ksp(libs.androidx.room.compiler)
    // storage access framework (SAF)
    implementation(libs.androidx.documentfile)
    // datastore for settings
    implementation(libs.androidx.datastore.preferences)

    // JSON parser
    implementation(libs.kotlinx.serialization.json)

    //noinspection GradleDependency
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // permissions
    implementation(libs.kpermissions)
    // simple storage
    implementation(libs.anggrayudi.storage)
    implementation(libs.materialDialogsCore)

    // nextcloud api
    implementation(libs.nextcloud.sso)
    implementation(libs.retrofit)

    implementation(libs.glide)
    // Generates GeneratedAppGlideModule from the @GlideModule-annotated
    // MainAppGlideModule below -- without this, Glide logs "Failed to
    // find GeneratedAppGlideModule". ksp is used (not kapt) since the
    // app calls Glide.with(...) directly rather than the deprecated
    // generated GlideApp/GlideRequests API, which ksp doesn't support.
    ksp(libs.glide.ksp)

    // WorkManager -- replaces SyncService (foreground Service + raw
    // AlarmManager repeating alarm) for background recipe sync. No
    // FOREGROUND_SERVICE permission or persistent notification needed,
    // Doze/battery-optimization compliance is automatic, and periodic
    // work survives reboots without an app-side BOOT_COMPLETED receiver.
    implementation(libs.androidx.work.runtime.ktx)
}
