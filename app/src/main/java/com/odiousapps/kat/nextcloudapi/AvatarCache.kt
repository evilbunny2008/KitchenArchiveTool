/*
 * AvatarCache.kt
 *
 * Copyright 2026 by MicMun
 */
package com.odiousapps.kat.nextcloudapi

import android.content.Context
import java.io.File

/**
 * Caches avatar bytes on disk, keyed by account name, so an account's
 * avatar can be shown immediately from a previous fetch instead of
 * waiting on a fresh network request every single time a row/screen
 * with an avatar is shown.
 *
 * Deliberately just a flat cache with no explicit expiry: avatars rarely
 * change, and every read here is expected to be paired with a background
 * [AvatarFetcher] refresh that overwrites the cache (see call sites) --
 * so a stale cached image is only ever visible for the brief moment
 * before that refresh completes, never indefinitely.
 *
 * Uses [Context.getCacheDir] rather than internal/external storage:
 * this is exactly the kind of data Android's cache directory is for --
 * cheaply re-derivable from the network, safe for the OS to clear under
 * storage pressure without losing anything the app can't just re-fetch.
 */
object AvatarCache {
   private fun cacheDir(context: Context): File =
      File(context.cacheDir, "avatars").apply { mkdirs() }

   /**
    * Account names come from AccountManager, not user-typed input, so
    * collisions/traversal aren't a real risk -- sanitized anyway since
    * the exact allowed character set isn't part of any documented
    * contract this app can rely on.
    */
   private fun cacheFile(context: Context, accountName: String): File {
      val safeName = accountName.replace(Regex("[^A-Za-z0-9_.-]"), "_")
      return File(cacheDir(context), "$safeName.jpg")
   }

   /** Returns null if nothing is cached yet for this account, or the read fails. */
   fun read(context: Context, accountName: String): ByteArray? {
      val file = cacheFile(context, accountName)
      if (!file.exists()) return null
      return try {
         file.readBytes()
      } catch (_: Exception) {
         null
      }
   }

   /** Best-effort: a failed write just means the next load re-fetches over the network instead. */
   fun write(context: Context, accountName: String, bytes: ByteArray) {
      try {
         cacheFile(context, accountName).writeBytes(bytes)
      } catch (_: Exception) {
         // ignored, see doc comment above
      }
   }
}
