/*
 * MainAppGlideModule.kt
 *
 * Copyright 2026 by MicMun
 */
package com.odiousapps.kat

import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.module.AppGlideModule

/**
 * Required entry point for Glide's annotation processor.
 *
 * An @GlideModule-annotated AppGlideModule must exist for Glide to
 * generate GeneratedAppGlideModule at build time. Without it, Glide logs
 * "Failed to find GeneratedAppGlideModule" and silently ignores any
 * LibraryGlideModules on the classpath (e.g. the one contributed by
 * nextcloud-commons-sso-glide for loading images through Nextcloud's
 * single sign-on).
 *
 * No overrides are needed here -- this class only needs to exist and
 * carry the annotation so the ksp/kapt processor picks it up.
 *
 * @author MicMun
 * @version 1.0, 04.09.26
 */
@GlideModule
class MainAppGlideModule : AppGlideModule()
