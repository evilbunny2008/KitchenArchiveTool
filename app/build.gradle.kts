// Several AGP Variant API members used below (outputFileName, artifacts.get,
// onVariants/selector for this variant-configuration style) are still
// marked @Incubating - meaning they work correctly today but the API
// surface could change in a future AGP release, not that anything here is
// broken. This is the standard, conventional way to suppress that specific
// warning category for the whole build script.
@file:Suppress("UnstableApiUsage")

import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.odiousapps.nextcloudcookbook"
    compileSdk {
        version = release(37)
    }

    buildFeatures {
        buildConfig = true
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
        applicationId = "com.odiousapps.mx3buttonmapper"
        minSdk = 29
        targetSdk = 37
        versionCode = 40000000
        versionName = "4.0.0"
        vectorDrawables.useSupportLibrary = true
        multiDexEnabled true
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
      coreLibraryDesugaringEnabled true
      sourceCompatibility = JavaVersion.VERSION_21
      targetCompatibility = JavaVersion.VERSION_21
   }

   buildFeatures {
      viewBinding true
      dataBinding true
      compose = true
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
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val appName = "MX3ButtonMapper"
        val versionName = variant.outputs.first().versionName
        val variantNameCapitalized = variant.name.replaceFirstChar { it.uppercase() }
        val ideListingTaskName = "produce${variantNameCapitalized}BundleIdeListingFile"

        // APK variant outputs support a directly settable filename, unlike
        // the bundle (AAB) case above - no separate rename/copy task needed.
        variant.outputs.forEach { output ->
            output.outputFileName.set("$appName-${versionName.get()}.apk")
        }

        val renameBundle = tasks.register("renameBundle$variantNameCapitalized") {
            group = "build"
            description = "Renames the $variantNameCapitalized .aab in place to $appName-<versionName>.aab"
            mustRunAfter(ideListingTaskName)
            doLast {
                val bundleFile = variant.artifacts.get(SingleArtifact.BUNDLE).get().asFile
                if (bundleFile.exists()) {
                    val renamedFile = File(bundleFile.parentFile, "$appName-${versionName.get()}.aab")
                    bundleFile.copyTo(renamedFile, overwrite = true)
                    bundleFile.delete()
                } else {
                    println("Expected bundle file not found at $bundleFile - skipping rename")
                }
            }
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
   implementation fileTree(dir: "libs", include: ["*.jar"])
   implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.3.21")

   implementation("org.jsoup:jsoup:1.23.2")

   implementation("androidx.core:core-ktx:1.19.0")
   implementation("androidx.legacy:legacy-support-v4:1.0.0")
   implementation("androidx.appcompat:appcompat:1.8.0")
   implementation("androidx.constraintlayout:constraintlayout:2.2.2")
   implementation("androidx.recyclerview:recyclerview:1.4.0")
   implementation("androidx.navigation:navigation-fragment-ktx:2.10.0")
   implementation("androidx.navigation:navigation-ui-ktx:2.10.0")
   implementation("androidx.preference:preference-ktx:2.10.0")
   implementation("androidx.legacy:legacy-preference-v14:1.0.0")
   // material design and viewpager2
   implementation("com.google.android.material:material:1.14.0")
   implementation("androidx.viewpager2:viewpager2:1.1.0")
   // Lifecycle dependencies
   //implementation "androidx.lifecycle:lifecycle-extensions:$version_lifecycle_extensions"
   implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.11.0")
   // Coroutines
   implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
   implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
   // room database
   implementation("androidx.room:room-runtime:2.8.4")
   // optional - Kotlin Extensions and Coroutines support for Room
   implementation("androidx.room:room-ktx:2.8.4")
   // storage access framework (SAF)
   implementation("androidx.documentfile:documentfile:1.1.0")
   // datastore for settings
   implementation "androidx.datastore:datastore-preferences:1.2.1"

   // Json parser
   implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

   //noinspection GradleDependency
   coreLibraryDesugaring('com.android.tools:desugar_jdk_libs:2.0.3')

   // permissions
   implementation('com.github.fondesa:kpermissions:3.5.0')
   // simple storage
   implementation('com.anggrayudi:storage:3.0.1')
   implementation('com.afollestad.material-dialogs:core:3.3.0')

   //nextcloud api
   implementation("com.github.nextcloud:Android-SingleSignOn:0.8.1")
   implementation('com.squareup.retrofit2:retrofit:3.0.0')
   implementation('com.github.stefan-niedermann.nextcloud-commons:sso-glide:1.8.2')

   implementation('com.github.bumptech.glide:glide:5.0.9')
}
