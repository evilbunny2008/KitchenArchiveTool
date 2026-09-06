@file:Suppress("UnstableApiUsage")

import com.android.build.api.artifact.SingleArtifact
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
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
        versionCode = 2
        versionName = "0.0.2"
        // Only relevant for API < 21 (vector drawables aren't natively
        // supported by the platform before Lollipop) -- moot with minSdk 29.
        // vectorDrawables.useSupportLibrary = true
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

    // Silences "Unable to strip the following libraries, packaging them as
    // they are: libdatastore_shared_counter.so" in the build log. That .so
    // (from AndroidX DataStore, used for its multi-process file-locking)
    // ships from Google already stripped of debug symbols -- `nm` on it
    // shows no symbols at all -- so AGP's strip step has nothing to do and
    // was only ever failing at that no-op, hence the warning. Telling AGP
    // to keep whatever's already there for this one file skips the strip
    // attempt entirely instead of attempting and warning on failure; since
    // there's nothing in it to strip either way, this changes nothing
    // about the built APK, just the build log.
    packaging {
        jniLibs {
            keepDebugSymbols += "**/libdatastore_shared_counter.so"
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

// Copies the release .aab from AGP's default build output location
// (app/build/outputs/bundle/release/app-release.aab) to
// app/dist/<appName>-<versionName>.aab (already gitignored) -- a
// separate, deliberately-chosen destination outside the build/ directory,
// so it survives a clean build. (Originally this copied to app/release/
// instead: don't rename it back to that. app/release/ turned out to
// collide with Android Studio's own "Generate Signed Bundle" wizard,
// which independently remembers/defaults to a <module>/release/
// destination of its own and writes its own app-release.aab there on
// every signed-bundle build -- completely unrelated to this task, but
// landing in the exact same folder, which made it look like this task
// wasn't deleting the original when actually a second, IDE-driven copy
// was reappearing after each build. dist/ doesn't collide with anything.)
// Critically, this must run *after* AGP's own internal
// "produce...BundleIdeListingFile" task, which declares the bundle at
// its default name/location as one of its own inputs - deleting it any
// earlier fails that task's input validation with "file doesn't exist".
//
// Defined as a proper typed task class (not a closure passed to
// tasks.register) with Provider/Property-typed inputs: an earlier version
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

    @get:OutputFile
    abstract val destinationFile: RegularFileProperty

    @TaskAction
    fun rename() {
        val file = bundleFile.get().asFile
        logger.lifecycle("renameBundle: source bundle at $file (exists=${file.exists()})")
        if (file.exists()) {
            val destination = destinationFile.get().asFile
            destination.parentFile.mkdirs()
            file.copyTo(destination, overwrite = true)
            logger.lifecycle("renameBundle: copied to $destination")
            // File.delete() never throws on failure, it just returns false --
            // check it explicitly so a failed delete (e.g. something else
            // still has the source file open/locked at this point) shows up
            // in the log instead of silently leaving the original behind
            // with no indication why.
            if (file.delete()) {
                logger.lifecycle("renameBundle: removed original $file")
            } else {
                logger.warn("renameBundle: could not delete original $file after copying -- it may be locked by another process; the copy at $destination is still correct")
            }
        } else {
            logger.lifecycle("renameBundle: expected bundle file not found at $file - skipping rename")
        }
    }
}

// Copies the release APK(s) from AGP's default build output location
// (app/build/outputs/apk/release/) to app/dist/ too, alongside the
// renamed bundle above. Unlike RenameBundleTask, this doesn't delete the
// originals -- there's no equivalent reason to (no known collision with
// anything else that writes to the APK output directory), so this is a
// plain copy, not a move.
//
// APK artifacts are exposed via SingleArtifact.APK: despite the name, this
// resolves to a *directory* (marked Artifact.ContainsMany in AGP's own
// docs), since a variant can in principle produce more than one APK
// (per-ABI splits, etc.), even though this project's release variant only
// ever produces one. Copying every .apk file found in that directory
// handles both cases without needing to special-case one vs. many, and
// without needing AGP's BuiltArtifactsLoader machinery (which exists for
// reading the accompanying metadata file precisely -- not needed here
// since a plain file-extension filter already skips it).
abstract class CopyApkTask : DefaultTask() {
    @get:InputFiles
    abstract val apkDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val destinationDirectory: DirectoryProperty

    @TaskAction
    fun copy() {
        val srcDir = apkDirectory.get().asFile
        val destDir = destinationDirectory.get().asFile
        destDir.mkdirs()

        val apkFiles = srcDir.listFiles { f -> f.extension == "apk" } ?: emptyArray()
        logger.lifecycle("copyApk: found ${apkFiles.size} apk file(s) in $srcDir")
        apkFiles.forEach { apk ->
            val dest = File(destDir, apk.name)
            apk.copyTo(dest, overwrite = true)
            logger.lifecycle("copyApk: copied to $dest")
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

        // outputFileName only renames the file within AGP's default output
        // directory (app/build/outputs/apk/release/) -- getting it into
        // app/dist/ too still needs the separate copyApk task below, same
        // as the bundle.
        variant.outputs.forEach { output ->
            output.outputFileName.set("$appName-${versionName.get()}.apk")
        }

        val renameBundle = tasks.register("renameBundle$variantNameCapitalized", RenameBundleTask::class.java) {
            group = "build"
            description = "Copies the $variantNameCapitalized .aab to app/dist/$appName-<versionName>.aab"
            mustRunAfter(ideListingTaskName)
            bundleFile.set(variant.artifacts.get(SingleArtifact.BUNDLE))
            destinationFile.set(layout.projectDirectory.file("dist/$appName-${versionName.get()}.aab"))
        }

        val copyApk = tasks.register("copyApk$variantNameCapitalized", CopyApkTask::class.java) {
            group = "build"
            description = "Copies the $variantNameCapitalized apk(s) to app/dist/"
            apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
            destinationDirectory.set(layout.projectDirectory.dir("dist"))
        }

        // Hooks both onto their standard task graphs, so they also run
        // automatically from Android Studio's Build menu flows (which
        // invoke bundleRelease/assembleRelease directly), not just when
        // run explicitly by name.
        afterEvaluate {
            tasks.named("bundle$variantNameCapitalized") {
                finalizedBy(renameBundle)
            }
            tasks.named("assemble$variantNameCapitalized") {
                finalizedBy(copyApk)
            }
        }
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.legacy.support.v4)
    // For encrypting the recipe-import app password at rest (see
    // RecipeImportCredentialStore). EncryptedSharedPreferences is
    // deprecated as of security-crypto 1.1.0-alpha07 in favor of
    // DataStore+Tink, but it's still fully functional in this stable
    // 1.1.0 release, and it's a well-tested, well-understood API --
    // hand-rolling fresh Tink/Keystore code for this instead isn't
    // something that can be properly verified without a real device's
    // Keystore to test against, and getting encryption code subtly wrong
    // is worse than using a proven (if deprecated) library. Worth
    // revisiting if/when a mature DataStore+Tink helper becomes standard.
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.preference.ktx)
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
