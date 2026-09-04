/*
 * RecipeDeleter.kt
 *
 * Copyright 2026 by MicMun
 */
package com.odiousapps.kat.nextcloudapi

import android.content.Context
import android.util.Log
import com.odiousapps.kat.util.Filesystem
import org.json.JSONObject
import java.io.File

/**
 * Deletes a recipe: first from the server (via the currently active
 * account), then -- only if that succeeds -- the local recipe folder.
 * Deleting locally regardless of server outcome would just have the
 * recipe silently reappear on the next sync, since as far as the server
 * is concerned nothing happened.
 *
 * Must be called from a background thread -- this performs a network
 * request via [com.nextcloud.android.sso.api.NextcloudAPI.performNetworkRequestV2],
 * same as the rest of the nextcloudapi package.
 */
class RecipeDeleter(private val context: Context) {

   companion object {
      private val TAG = RecipeDeleter::class.toString()
   }

   sealed class Result {
      object Success : Result()
      data class Failure(val reason: String) : Result()
   }

   /**
    * @param recipeFolder the recipe's local folder (the parent of its
    *   recipe.json -- see DbRecipe.recipeCore.fileSystem.filePath), which
    *   also holds the sibling METADATA file (see Sync.kt) this needs to
    *   find the recipe's server-side id.
    */
   fun deleteRecipe(recipeFolder: File): Result {
      val metadataFile = File(recipeFolder, Sync.METADATA)
      val recipeId = try {
         JSONObject(metadataFile.readText()).getString("recipe_id")
      } catch (e: Exception) {
         Log.e(TAG, "Could not read recipe id from METADATA: ${e.message}")
         return Result.Failure("Could not find this recipe on the server")
      }

      val api = Accounts(context).getApiToAccount()
         ?: return Result.Failure("No active account")

      val deleted = api.use { api ->
          CookbookAPI(api).deleteRecipe(recipeId)
      }

      if (!deleted) {
         return Result.Failure("The server didn't confirm the delete")
      }

      Filesystem(context).deleteRecursive(recipeFolder)
      return Result.Success
   }
}
