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
 * Copies a single recipe's text/structured data (name, ingredients,
 * instructions, times, nutrition, etc.) from the recipe.json this app
 * already has cached locally to a *different* Nextcloud account's
 * Cookbook instance.
 *
 * The photo does not carry over. The Cookbook REST API has no endpoint
 * to upload image bytes when creating a recipe -- an image can only be
 * attached by referencing a file already present in that account's own
 * Nextcloud storage. This is an open upstream limitation, not something
 * this app can work around:
 * https://github.com/nextcloud/cookbook/issues/727
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
       * onto a different server: "id"/"recipe_id" belong to the source
       * recipe on the source server and are meaningless (or, worse,
       * could collide with an unrelated recipe) on the destination;
       * the image fields point at a location on the source server that
       * doesn't exist on the destination.
       */
      private val FIELDS_TO_STRIP = listOf("id", "recipe_id", "image", "imageUrl", "thumbImageUrl", "fullImageUrl")
   }

   sealed class Result {
      data class Success(val newRecipeId: String) : Result()
      data class Failure(val reason: String) : Result()
   }

   /**
    * @param recipeJsonFile the local recipe.json this recipe was last synced from
    *   (DbRecipe.recipeCore.fileSystem.filePath)
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
}
