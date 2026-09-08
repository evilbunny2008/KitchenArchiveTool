/*
 * RecipeImportClient.kt
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
 * Talks to the server-side recipe-import bridge script (see
 * import_recipe.php in this project's companion server-side tooling) --
 * posts the recipe URL to scrape+convert, and returns the resulting
 * recipe JSON-LD. Uploading it into Nextcloud Cookbook is this app's own
 * job (see CookbookAPI.createRecipe(), called with whichever account this
 * app is already authenticated as) -- the bridge only ever sees a URL,
 * never any Nextcloud credentials for any account.
 *
 * Plain HTTP, not the Nextcloud SSO library: this talks to a *different*
 * server entirely (wherever the bridge script is deployed), not to any
 * Nextcloud instance directly.
 *
 * Must be called from a background thread.
 */
object RecipeImportClient {

   sealed class Result {
      data class Success(val recipe: JSONObject) : Result()
      data class Failure(val reason: String) : Result()
   }

   private const val CONNECT_TIMEOUT_MS = 15000

   // Scraping the source page can genuinely take a while on a slow site --
   // much longer than a typical API call, so this gets a longer allowance
   // than the rest of the nextcloudapi package's requests.
   private const val READ_TIMEOUT_MS = 60000

   fun importRecipe(serviceUrl: String, recipeUrl: String): Result {
      val url = try {
         URL(serviceUrl)
      } catch (e: Exception) {
         return Result.Failure("Invalid recipe import service URL: ${e.message}")
      }

      val connection = (url.openConnection() as HttpURLConnection).apply {
         requestMethod = "POST"
         doOutput = true
         connectTimeout = CONNECT_TIMEOUT_MS
         readTimeout = READ_TIMEOUT_MS
         setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
      }

      return try {
         val body = "recipe_url=" + URLEncoder.encode(recipeUrl, "UTF-8")
         connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }

         val responseCode = connection.responseCode
         val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
         val responseBody = stream?.bufferedReader()?.use { it.readText() } ?: ""

         val json = try {
            JSONObject(responseBody)
         } catch (_: Exception) {
            null
         }

         if (responseCode in 200..299 && json?.has("recipe") == true) {
            Result.Success(json.getJSONObject("recipe"))
         } else {
            // import_recipe.php's own error responses put the useful
            // detail under "details" (relayed from the Python script's
            // own stderr) or "error" (its own validation failures) --
            // prefer whichever is actually present rather than assuming.
            val reason = json?.optString("details")?.takeIf { it.isNotBlank() }
               ?: json?.optString("error")?.takeIf { it.isNotBlank() }
               ?: "HTTP $responseCode"
            Result.Failure(reason)
         }
      } catch (e: IOException) {
         Result.Failure(e.message ?: "Network error")
      } finally {
         connection.disconnect()
      }
   }
}
