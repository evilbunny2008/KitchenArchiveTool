package com.odiousapps.kat.nextcloudapi

import android.net.Uri
import android.util.Log
import com.nextcloud.android.sso.QueryParam
import com.nextcloud.android.sso.aidl.NextcloudRequest
import com.nextcloud.android.sso.api.NextcloudAPI
import com.nextcloud.android.sso.exceptions.NextcloudHttpRequestFailedException
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.*
import java.util.logging.Logger
import kotlin.collections.ArrayList

class CookbookAPI(private val mApi: NextcloudAPI) {

   companion object {
      private const val API_RECIPE_BASE = "/index.php/apps/cookbook/api/v1/recipes"
      private val TAG = CookbookAPI::class.toString()
   }

   fun getRecipes(): ArrayList<String> {
      val result = ArrayList<String>()

      val nextcloudRequest: NextcloudRequest = NextcloudRequest.Builder()
         .setMethod("GET")
         .setUrl(Uri.encode(API_RECIPE_BASE, "/"))
         .build()

      try {
         mApi.performNetworkRequestV2(nextcloudRequest).body.bufferedReader().use { r ->
            var json = ""

            var line: String?
            while (r.readLine().also { line = it } != null) {
               Logger.getLogger(this::class.java.name).warning(line)
               json += line + '\n'
            }
            val root = JSONArray(json)
            for (i in 0 until root.length()) {
               result.add(root.getJSONObject(i).toString())
            }
         }
      } catch (e: Exception) {
         Logger.getLogger(this::class.java.name).severe("Unknown Exception in getRecipes: ${e.javaClass}: ${e.message}")
      }
      return result
   }

   fun getRecipe(id: String): String {
      var result = ""

      val nextcloudRequest: NextcloudRequest = NextcloudRequest.Builder()
         .setMethod("GET")
         .setUrl(Uri.encode("$API_RECIPE_BASE/$id", "/"))
         .build()

      try {
         mApi.performNetworkRequestV2(nextcloudRequest).body.bufferedReader().use { r ->
            var line: String?
            while (r.readLine().also { line = it } != null) {
               result += line + '\n'
            }
         }

      } catch (e: Exception) {
         Logger.getLogger(this::class.java.name).severe("Unknown Exception in getRecipe: ${e.javaClass}: ${e.message}")
      }
      return result
   }

   fun getImage(id: String, size: String = "full"): ByteArray? {

      val url = "$API_RECIPE_BASE/$id/image?size=$size"
      val parameters = Collections.singleton(QueryParam("size", size))
      val nextcloudRequest: NextcloudRequest = NextcloudRequest.Builder()
         .setMethod("GET")
         .setUrl(url)
         .setParameter(parameters)
         .build()

      try {
         mApi.performNetworkRequestV2(nextcloudRequest).body.use { body ->
            try {
               val byteArray = body.readBytes()

               if (String(byteArray).startsWith("<svg")) {
                  // This image is the default svg. Do not store.
                  return null
               }

               return byteArray
            } catch (e: Exception) {
               Logger.getLogger(this::class.java.name).severe("Unknown Exception in getImage, could not read bytes: ${e.javaClass}: ${e.message}")
            }
         }

      } catch (e: NextcloudHttpRequestFailedException) {
         Logger.getLogger(this::class.java.name).severe("Nextcloud Http Exception: ${e.message}")
      } catch (e: Exception) {
         Logger.getLogger(this::class.java.name).severe("Unknown Exception in getImage: ${e.javaClass}: ${e.message}")
      }
      return null
   }

   fun createRecipe(recipe: JSONObject): String? {
      val nextcloudRequest: NextcloudRequest = NextcloudRequest.Builder()
         .setMethod("POST")
         .setRequestBody(recipe.toString())
         .setUrl(API_RECIPE_BASE)
         .build()

      try {
         mApi.performNetworkRequestV2(nextcloudRequest).body.use { body ->
            try {
               val byteArray = body.readBytes()
               Log.d(TAG, "Result: " + String(byteArray))
               return String(byteArray)
            } catch (e: IOException) {
               Logger.getLogger(this::class.java.name).severe("IOException in createRecipe: ${e.javaClass}: ${e.message}")
            }
         }

      } catch (e: Exception) {
         Logger.getLogger(this::class.java.name).severe("Unknown Exception in createRecipe: ${e.javaClass}: ${e.message}")
      }
      return null
   }
}
