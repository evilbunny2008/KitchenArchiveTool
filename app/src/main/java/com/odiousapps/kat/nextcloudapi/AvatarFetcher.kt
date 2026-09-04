/*
 * AvatarFetcher.kt
 *
 * Copyright 2026 by MicMun
 */
package com.odiousapps.kat.nextcloudapi

import android.net.Uri
import com.nextcloud.android.sso.aidl.NextcloudRequest
import com.nextcloud.android.sso.api.NextcloudAPI
import com.nextcloud.android.sso.model.SingleSignOnAccount
import java.util.logging.Logger

/**
 * Fetches a Nextcloud account's avatar image as raw bytes over an
 * authenticated [NextcloudAPI] connection.
 *
 * Deliberately NOT using nextcloud-commons:sso-glide's Glide integration
 * (SingleSignOnUrl / the deprecated plain-String loading) for this: both
 * of its Glide loading paths -- see StringLoader.kt and
 * SingleSignOnUrlLoader.kt in that library's own source -- build the
 * exact same underlying `AbstractStreamFetcher`, whose `cleanup()` is a
 * documented no-op ("Nothing to do here..."). That means the InputStream
 * it hands to Glide (and the ParcelFileDescriptor backing the AIDL
 * response underneath it) is never closed once Glide is done decoding
 * it -- confirmed via a StrictMode LeakedClosableViolation stack trace
 * pointing directly at
 * it.niedermann.nextcloud.sso.glide.AbstractStreamFetcher.loadData, and
 * confirmed unconditional (present on the library's current master, and
 * not something callers can work around by how they invoke it) by
 * reading the library's own source directly.
 *
 * Fetching the bytes ourselves and handing Glide the resulting
 * ByteArray instead avoids Glide's network fetcher -- and this leaky
 * library path -- entirely. Same pattern already used correctly
 * elsewhere in this app (see CookbookAPI.getImage()).
 *
 * Must be called from a background thread -- this performs a network
 * request via [NextcloudAPI.performNetworkRequestV2].
 */
object AvatarFetcher {
   private val TAG = AvatarFetcher::class.toString()

   /**
    * @param api an already-open connection for the account whose avatar
    *   this is -- not closed by this function, callers own its lifecycle
    * @param ssoAccount the same account [api] is connected as
    */
   fun fetchAvatarBytes(api: NextcloudAPI, ssoAccount: SingleSignOnAccount, size: Int = 64): ByteArray? {
      val nextcloudRequest = NextcloudRequest.Builder()
         .setMethod("GET")
         .setUrl(Uri.encode("/index.php/avatar/${ssoAccount.userId}/$size", "/"))
         .build()

      return try {
         api.performNetworkRequestV2(nextcloudRequest).body.use { it.readBytes() }
      } catch (e: Exception) {
         Logger.getLogger(TAG).severe("Could not fetch avatar: ${e.javaClass}: ${e.message}")
         null
      }
   }
}
