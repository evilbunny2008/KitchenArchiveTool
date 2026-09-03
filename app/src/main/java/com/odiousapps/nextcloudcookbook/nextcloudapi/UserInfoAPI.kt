/*
 * UserInfoAPI.kt
 */
package com.odiousapps.nextcloudcookbook.nextcloudapi

import android.net.Uri
import android.util.Log
import com.nextcloud.android.sso.QueryParam
import com.nextcloud.android.sso.aidl.NextcloudRequest
import com.nextcloud.android.sso.api.NextcloudAPI
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Collections

/**
 * Fetches basic profile info for the account a NextcloudAPI instance is
 * authenticated as. The SSO library only exposes login-level identifiers
 * (account name, userId) -- the actual human-readable display name only
 * exists on the server, via the OCS Provisioning API.
 *
 * @author MicMun
 * @version 1.1
 */
class UserInfoAPI(private val mApi: NextcloudAPI) {

   companion object {
      private const val API_USER = "/ocs/v2.php/cloud/user"
      private val TAG = UserInfoAPI::class.java.simpleName
   }

   /**
    * Returns the account's display name (e.g. "John Smith"), or null if it
    * couldn't be fetched (offline, server error, unexpected response shape).
    */
   fun getDisplayName(): String? {
      val nextcloudRequest: NextcloudRequest = NextcloudRequest.Builder()
         .setMethod("GET")
         .setUrl(Uri.encode(API_USER, "/"))
         .setParameter(Collections.singleton(QueryParam("format", "json")))
         .build()

      return try {
         val istream = mApi.performNetworkRequestV2(nextcloudRequest)
         val json = BufferedReader(InputStreamReader(istream.body)).readText()
         Log.d(TAG, "Raw response from $API_USER: $json")
         val data = JSONObject(json).getJSONObject("ocs").getJSONObject("data")
         // field name has varied across server versions ("display-name" vs "displayname")
         val name = when {
            data.has("displayname") -> data.getString("displayname")
            data.has("display-name") -> data.getString("display-name")
            else -> {
               Log.w(TAG, "No displayname/display-name field. Keys present: ${data.keys().asSequence().toList()}")
               null
            }
         }
         Log.d(TAG, "Parsed display name: $name")
         name
      } catch (e: Exception) {
         Log.w(TAG, "Could not fetch display name: ${e.javaClass.simpleName}: ${e.message}")
         null
      }
   }
}
