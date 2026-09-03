/*
 * RecipeCopier.kt
 *
 * Copyright 2026 by MicMun
 */
package com.odiousapps.kat.nextcloudapi

import android.content.Context
import android.util.Log
import com.google.gson.GsonBuilder
import com.nextcloud.android.sso.AccountImporter
import com.nextcloud.android.sso.api.NextcloudAPI
import org.json.JSONObject
import java.io.File

/**
 * Copies a single recipe's data, including its photo, from the recipe
 * this app already has cached locally to a *different* Nextcloud
 * account's Cookbook instance.
 *
 * The photo is carried over by URL, not by uploading bytes: Cookbook's
 * own documentation confirms that if a recipe's "image" field is a URL
 * rather than a local path, the server downloads it itself when the
 * recipe is created -- https://nextcloud.github.io/cookbook/user/ ("The
 * image can be loaded from a URL. Just type or paste the URL in the
 * field. The cookbook app will download and use the image."). So this
 * class points "image" at the *source* server's own image endpoint for
 * this recipe and lets the *destination* server fetch it from there.
 *
 * Whether that fetch actually succeeds depends on whether the
 * destination server can reach the source server's image URL without
 * being logged in as the source account (this app's own SSO credentials
 * aren't something a server-side HTTP request on a different Nextcloud
 * instance can present). If the source server requires auth for that
 * URL, Cookbook will end up with no image rather than a broken one --
 * everything else about the recipe still copies over either way.
 *
 * Must be called from a background thread -- this performs network
 * requests via [NextcloudAPI.performNetworkRequestV2], same as the rest
 * of the nextcloudapi package.
 */
class RecipeCopier(private val context: Context) {

   companion object {
      private val TAG = RecipeCopier::class.toString()

      /**
       * Keys from a downloaded recipe.json that must not be replayed
       * onto a different server: they identify the recipe on the
       * *source* server and are meaningless (or, worse, could collide
       * with an unrelated recipe) on the destination.
       */
      private val FIELDS_TO_STRIP = listOf("id", "recipe_id")
   }

   sealed class Result {
      data class Success(val newRecipeId: String) : Result()
      data class Failure(val reason: String) : Result()
   }

   /**
    * @param recipeJsonFile the local recipe.json this recipe was last synced from
    *   (DbRecipe.recipeCore.fileSystem.filePath) -- its sibling METADATA file
    *   (see Sync.kt) is also read, to find the source recipe's server-side id
    *   for building its image URL
    * @param destinationAccountName the "name" (as AccountManager/SSO knows it) of
    *   the account to copy into -- NOT the display name, see
    *   SingleSignOnAccount.name / AccountSwitcherBottomSheet
    */
   fun copyToAccount(recipeJsonFile: File, destinationAccountName: String): Result {
      if (!recipeJsonFile.exists()) {
         return Result.Failure("Local recipe file is missing")
      }

      val json = try {
         JSONObject(recipeJsonFile.readText())
      } catch (e: Exception) {
         Log.e(TAG, "Could not parse local recipe.json: ${e.message}")
         return Result.Failure("Could not read this recipe")
      }

      FIELDS_TO_STRIP.forEach { json.remove(it) }

      resolveSourceImageUrl(recipeJsonFile)?.let { json.put("image", it) }

      val destinationApi = try {
         val ssoAccount = AccountImporter.getSingleSignOnAccount(context, destinationAccountName)
         NextcloudAPI(context, ssoAccount, GsonBuilder().create())
      } catch (e: Exception) {
         Log.e(TAG, "Could not open destination account: ${e.message}")
         return Result.Failure("Could not access the destination account")
      }

      return try {
         val newId = CookbookAPI(destinationApi).createRecipe(json)
         if (newId != null) {
            Result.Success(newId)
         } else {
            // CookbookAPI.createRecipe() returns null on any failure, including
            // a name clash -- unlike Sync.pushJsonToRemote (which retries with
            // "(Copy)" appended for its own new-local-recipe upload flow), we
            // surface this directly so the person can decide what to do
            // (rename and retry, or accept the two recipes may be unrelated).
            Result.Failure("The destination server rejected the recipe (a recipe with this name may already exist there)")
         }
      } finally {
         destinationApi.close()
      }
   }

   /**
    * Builds a URL pointing at this recipe's full-size image on the
    * *source* server, for the destination server to download itself.
    * Returns null if the source recipe's server-side id can't be
    * determined (e.g. a purely local recipe with no METADATA file) or
    * there's no active source account -- callers should leave "image"
    * out of the payload in that case rather than send a broken URL.
    */
   private fun resolveSourceImageUrl(recipeJsonFile: File): String? {
      val metadataFile = File(recipeJsonFile.parentFile, Sync.METADATA)
      if (!metadataFile.exists()) return null

      val sourceRecipeId = try {
         JSONObject(metadataFile.readText()).getString("recipe_id")
      } catch (e: Exception) {
         Log.w(TAG, "Could not read source recipe id from METADATA: ${e.message}")
         return null
      }

      val sourceAccount = Accounts(context).getCurrentAccount() ?: return null
      return "${sourceAccount.url}${CookbookAPI.API_RECIPE_BASE}/$sourceRecipeId/image?size=full"
   }
}
