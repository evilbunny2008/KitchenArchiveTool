/*
 * NextcloudLoginFlow.kt
 *
 * Copyright 2026 by MicMun
 */
package com.odiousapps.kat.nextcloudapi

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Nextcloud's "Login Flow v2" -- the same mechanism the Nextcloud Files
 * app itself uses to grant this app SSO access, used here to obtain a
 * separate, independently-revocable app password for a different purpose
 * (authenticating the server-side recipe-import bridge script) without
 * this app ever seeing the account's real password. The user
 * authenticates on Nextcloud's own hosted page (shown in a WebView, see
 * RecipeImportLoginActivity), not anything this app renders itself.
 *
 * Protocol, per Nextcloud's own developer manual
 * (https://docs.nextcloud.com/server/latest/developer_manual/client_apis/LoginFlow/index.html#login-flow-v2):
 * 1. POST {server}/index.php/login/v2 (no body, no auth) -> a JSON object
 *    with a "login" URL to open in a browser/WebView, and a "poll" object
 *    ({token, endpoint}) to check for completion.
 * 2. The user authenticates and approves the grant at that "login" URL.
 * 3. Meanwhile, POST {poll.endpoint} with token={poll.token}
 *    (form-encoded) repeatedly. Returns non-200 (404 on some server
 *    versions, 302 on others -- treated identically here as "not
 *    approved yet") until the user finishes, then returns 200 exactly
 *    once with {server, loginName, appPassword}. The token expires after
 *    20 minutes.
 *
 * Must be called from a background thread -- this performs plain
 * blocking HTTP requests via HttpURLConnection, not the Nextcloud SSO
 * library (there's no account to route this through yet -- obtaining one
 * is the whole point).
 */
object NextcloudLoginFlow {

   private const val CONNECT_TIMEOUT_MS = 15000
   private const val READ_TIMEOUT_MS = 15000

   data class InitResult(val loginUrl: String, val pollToken: String, val pollEndpoint: String)
   data class Credentials(val server: String, val loginName: String, val appPassword: String)

   /** Throws IOException/JSONException on any network or parsing failure. */
   fun initiate(hostname: String): InitResult {
      val url = URL("${hostname.trimEnd('/')}/index.php/login/v2")
      val connection = (url.openConnection() as HttpURLConnection).apply {
         requestMethod = "POST"
         doOutput = false
         connectTimeout = CONNECT_TIMEOUT_MS
         readTimeout = READ_TIMEOUT_MS
      }

      try {
         val responseCode = connection.responseCode
         if (responseCode != HttpURLConnection.HTTP_OK) {
            throw IOException("Login flow init failed: HTTP $responseCode")
         }

         val body = connection.inputStream.bufferedReader().use { it.readText() }
         val json = JSONObject(body)
         val poll = json.getJSONObject("poll")
         return InitResult(
            loginUrl = json.getString("login"),
            pollToken = poll.getString("token"),
            pollEndpoint = poll.getString("endpoint"),
         )
      } finally {
         connection.disconnect()
      }
   }

   /**
    * Polls once. Returns Credentials the moment the user has approved the
    * request, or null if it hasn't been approved yet (call again after a
    * short delay). Nextcloud only ever returns the success response once
    * per token, so the caller must hold onto whatever this returns rather
    * than expecting to poll again for the same result.
    */
   fun poll(pollEndpoint: String, pollToken: String): Credentials? {
      val url = URL(pollEndpoint)
      val connection = (url.openConnection() as HttpURLConnection).apply {
         requestMethod = "POST"
         doOutput = true
         connectTimeout = CONNECT_TIMEOUT_MS
         readTimeout = READ_TIMEOUT_MS
         setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
      }

      try {
         val body = "token=" + URLEncoder.encode(pollToken, "UTF-8")
         connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }

         val responseCode = connection.responseCode
         if (responseCode != HttpURLConnection.HTTP_OK) {
            // Not approved yet -- observed as 404 on some server versions,
            // 302 on others, so treat anything other than 200 as "still
            // pending" rather than matching one specific code.
            connection.errorStream?.close()
            return null
         }

         val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
         val json = JSONObject(responseBody)
         return Credentials(
            server = json.getString("server"),
            loginName = json.getString("loginName"),
            appPassword = json.getString("appPassword"),
         )
      } finally {
         connection.disconnect()
      }
   }
}
