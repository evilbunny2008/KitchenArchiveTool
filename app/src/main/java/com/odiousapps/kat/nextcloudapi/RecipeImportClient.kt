/*
 * RecipeImportClient.kt
 *
 * Copyright 2026 by MicMun
 */
package com.odiousapps.kat.nextcloudapi

import android.content.Context
import android.webkit.WebSettings
import com.odiousapps.kat.BuildConfig
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.zip.GZIPOutputStream

/**
 * Talks to the server-side recipe-import bridge script (see
 * get_jsonld.php in this project's companion server-side tooling,
 * deployed at [SERVICE_URL]) -- downloads the recipe page on-device, posts
 * its HTML (plus the URL) to be converted, and returns the resulting recipe
 * JSON-LD. Uploading it into Nextcloud
 * Cookbook is this app's own job (see CookbookAPI.createRecipe(), called
 * with whichever account this app is already authenticated as) -- the
 * bridge only ever sees a URL and page HTML, never any Nextcloud
 * credentials for any account.
 *
 * Why download on-device: some recipe sites (e.g. EatingWell/Allrecipes)
 * reject requests from hosting-provider IP ranges outright, so the bridge
 * server can't fetch them itself, but serve the same page to a phone on a
 * home/mobile connection without fuss. If the on-device download fails
 * anyway, only the URL is sent and the bridge falls back to fetching it
 * itself, same as before.
 *
 * Plain HTTP, not the Nextcloud SSO library: this talks to a *different*
 * server entirely (odiousapps.com, not wherever the user's own Nextcloud
 * instance is).
 *
 * Must be called from a background thread.
 */
object RecipeImportClient {

   private const val SERVICE_URL = "https://recipes.odiousapps.com/get_jsonld.php"

   sealed class Result {
      data class Success(val recipe: JSONObject) : Result()
      data class Failure(val reason: String) : Result()
   }

   private const val CONNECT_TIMEOUT_MS = 15000

   // Scraping the source page can genuinely take a while on a slow site --
   // much longer than a typical API call, so this gets a longer allowance
   // than the rest of the nextcloudapi package's requests.
   private const val READ_TIMEOUT_MS = 60000

   private const val PAGE_TIMEOUT_MS = 20000

   // Keeps a runaway/huge response from being held in memory and uploaded
   // -- real recipe pages are well under this. Must also stay within the
   // bridge server's MAX_HTML_BYTES (get_jsonld.php), the decompressed
   // size it accepts.
   private const val MAX_PAGE_BYTES = 8 * 1024 * 1024

   // Same-protocol redirects are followed by HttpURLConnection itself;
   // this only bounds the cross-protocol (http <-> https) ones it won't.
   private const val MAX_REDIRECTS = 5

   fun importRecipe(context: Context, recipeUrl: String): Result {
      val url = try {
         URL(SERVICE_URL)
      } catch (e: Exception) {
         return Result.Failure("Invalid recipe import service URL: ${e.message}")
      }

      // gzipped before upload -- HTML typically shrinks 5-10x, which
      // matters on a mobile connection and keeps well under the bridge
      // server's PHP upload limits.
      val pageHtmlGz = downloadPage(context, recipeUrl)?.let { gzip(it) }
      val boundary = "----KAT" + UUID.randomUUID().toString().replace("-", "")

      val connection = (url.openConnection() as HttpURLConnection).apply {
         requestMethod = "POST"
         doOutput = true
         connectTimeout = CONNECT_TIMEOUT_MS
         readTimeout = READ_TIMEOUT_MS
         setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
         setRequestProperty("User-Agent", "KitchenArchiveTool/${BuildConfig.VERSION_NAME}")
      }

      return try {
         connection.outputStream.use { out ->
            fun writeText(text: String) = out.write(text.toByteArray(StandardCharsets.UTF_8))

            writeText("--$boundary\r\n")
            writeText("Content-Disposition: form-data; name=\"recipe_url\"\r\n\r\n")
            writeText("$recipeUrl\r\n")
            // Sent as a file part so PHP spools it to a temp file, which
            // get_jsonld.php decompresses for recipe_to_jsonld.py's --file.
            if (pageHtmlGz != null) {
               writeText("--$boundary\r\n")
               writeText("Content-Disposition: form-data; name=\"recipe_html\"; filename=\"page.html.gz\"\r\n")
               writeText("Content-Type: application/gzip\r\n\r\n")
               out.write(pageHtmlGz)
               writeText("\r\n")
            }
            writeText("--$boundary--\r\n")
         }

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
            // get_jsonld.php's own error responses put the useful detail
            // under "details" (relayed from the Python script's own
            // stderr) or "error" (its own validation failures) -- prefer
            // whichever is actually present rather than assuming.
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

   private fun gzip(data: ByteArray): ByteArray {
      val buffer = ByteArrayOutputStream()
      GZIPOutputStream(buffer).use { it.write(data) }
      return buffer.toByteArray()
   }

   /**
    * Fetches the recipe page's raw bytes from this device's own network
    * connection, or null on any failure (the bridge then fetches the URL
    * itself instead). Uses the device's real WebView User-Agent so the
    * request looks like the phone's own browser, which is what these
    * sites expect to see from a phone anyway.
    */
   private fun downloadPage(context: Context, recipeUrl: String): ByteArray? {
      val userAgent = try {
         WebSettings.getDefaultUserAgent(context)
      } catch (_: Exception) {
         // No WebView provider installed/enabled on this device.
         "KitchenArchiveTool/${BuildConfig.VERSION_NAME}"
      }

      var currentUrl = try {
         URL(recipeUrl)
      } catch (_: Exception) {
         return null
      }

      repeat(MAX_REDIRECTS + 1) {
         val connection = try {
            (currentUrl.openConnection() as HttpURLConnection).apply {
               connectTimeout = PAGE_TIMEOUT_MS
               readTimeout = PAGE_TIMEOUT_MS
               instanceFollowRedirects = true
               setRequestProperty("User-Agent", userAgent)
               setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
               setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag())
            }
         } catch (_: IOException) {
            return null
         }

         try {
            val responseCode = connection.responseCode
            if (responseCode in 300..399) {
               val location = connection.getHeaderField("Location") ?: return null
               currentUrl = URL(currentUrl, location)
               return@repeat
            }
            if (responseCode !in 200..299) return null

            connection.inputStream.use { input ->
               val buffer = ByteArrayOutputStream()
               val chunk = ByteArray(16 * 1024)
               while (true) {
                  val read = input.read(chunk)
                  if (read < 0) break
                  if (buffer.size() + read > MAX_PAGE_BYTES) return null
                  buffer.write(chunk, 0, read)
               }
               return buffer.toByteArray()
            }
         } catch (_: IOException) {
            return null
         } finally {
            connection.disconnect()
         }
      }
      return null
   }
}
