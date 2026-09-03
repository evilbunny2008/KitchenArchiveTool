/*
 * WebDavClient.kt
 *
 * Copyright 2026 by MicMun
 */
package com.odiousapps.kat.nextcloudapi

import android.net.Uri
import android.util.Log
import com.nextcloud.android.sso.aidl.NextcloudRequest
import com.nextcloud.android.sso.api.NextcloudAPI
import com.nextcloud.android.sso.model.SingleSignOnAccount
import java.io.ByteArrayInputStream

/**
 * Minimal WebDAV client for uploading/deleting a single file in the
 * signed-in account's own Nextcloud storage. Used by [RecipeCopier] to
 * stage a recipe's photo in the *destination* account's own files before
 * referencing it by a local path when creating the recipe there -- see
 * RecipeCopier's doc comment for why that's necessary.
 *
 * Must be called from a background thread, same as the rest of the
 * nextcloudapi package.
 */
class WebDavClient(private val api: NextcloudAPI, private val account: SingleSignOnAccount) {

   companion object {
      private val TAG = WebDavClient::class.toString()
   }

   /** @param path path relative to the account's files root, e.g. "/kat_temp.jpg" */
   fun uploadFile(path: String, bytes: ByteArray): Boolean {
      val request = NextcloudRequest.Builder()
         .setMethod("PUT")
         .setUrl(Uri.encode("/remote.php/dav/files/${account.userId}$path", "/"))
         .setRequestBodyAsStream(ByteArrayInputStream(bytes))
         .build()

      return try {
         api.performNetworkRequestV2(request).body.use { }
         true
      } catch (e: Exception) {
         Log.e(TAG, "Could not upload $path: ${e.javaClass}: ${e.message}")
         false
      }
   }

   /**
    * Best-effort cleanup -- failures here are only logged, not surfaced,
    * since by the time this runs the actual operation this file was
    * staged for has already succeeded or failed on its own.
    */
   fun deleteFile(path: String) {
      val request = NextcloudRequest.Builder()
         .setMethod("DELETE")
         .setUrl(Uri.encode("/remote.php/dav/files/${account.userId}$path", "/"))
         .build()

      try {
         api.performNetworkRequestV2(request).body.use { }
      } catch (e: Exception) {
         Log.w(TAG, "Could not clean up temp file $path: ${e.javaClass}: ${e.message}")
      }
   }
}
