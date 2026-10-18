/*
 * RecipeEditor.kt
 */
package com.odiousapps.kat.nextcloudapi

import android.content.Context
import android.util.Log
import com.odiousapps.kat.util.Filesystem
import org.json.JSONObject
import java.io.File

/**
 * Saves an edited recipe: first to the server (via the currently active
 * account), then -- only if that succeeds -- to the local copy, so the
 * list reflects the edit straight away instead of waiting for the next
 * sync (which may be a while off with "sync on Wi-Fi only").
 *
 * Local recipe folders are named after the recipe (see Sync.downloadRecipe),
 * and the local DB is keyed by that name too, so a rename also has to move
 * the folder. Leaving it where it was would make the next sync download a
 * second copy under the new name, while the old folder -- whose METADATA
 * still carries a recipe_id the server knows about -- would never be
 * cleaned up by Sync.cleanOldRecipes().
 *
 * Must be called from a background thread -- this performs network
 * requests, same as the rest of the nextcloudapi package.
 */
class RecipeEditor(private val context: Context) {

   companion object {
      private val TAG = RecipeEditor::class.toString()
   }

   sealed class Result {
      /** [newName] is the name the server ended up storing, which Cookbook may have sanitised. */
      data class Success(val newName: String, val renamed: Boolean) : Result()
      data class Failure(val reason: String) : Result()
   }

   /**
    * @param recipeJsonFile the local recipe.json being edited
    *   (DbRecipe.recipeCore.fileSystem.filePath); its sibling METADATA file
    *   is read for the recipe's server-side id
    * @param edited the full recipe JSON to store
    */
   fun saveRecipe(recipeJsonFile: File, edited: JSONObject): Result {
      val oldFolder = recipeJsonFile.parentFile
         ?: return Result.Failure("Could not find this recipe locally")

      val recipeId = try {
         JSONObject(File(oldFolder, Sync.METADATA).readText()).getString("recipe_id")
      } catch (e: Exception) {
         Log.e(TAG, "Could not read recipe id from METADATA: ${e.message}")
         return Result.Failure("Could not find this recipe on the server")
      }

      val api = Accounts(context).getApiToAccount()
         ?: return Result.Failure("No active account")

      // Re-read what the server actually stored rather than trusting our
      // own payload: Cookbook normalises fields (and may tweak the name)
      // on save, and the local copy should match what the next sync would
      // download anyway.
      val stored = api.use { api ->
         val cookbook = CookbookAPI(api)
         if (!cookbook.updateRecipe(recipeId, edited)) {
            return Result.Failure("The server rejected the changes (another recipe may already have this name)")
         }
         cookbook.getRecipe(recipeId)
      }

      val storedJson = try {
         JSONObject(stored)
      } catch (e: Exception) {
         // The edit is already saved on the server; the next sync will
         // bring the local copy up to date, so this isn't a failure.
         Log.w(TAG, "Could not re-fetch recipe after saving: ${e.message}")
         edited
      }
      val newName = storedJson.optString("name").ifEmpty { edited.optString("name") }

      var folder = oldFolder
      val renamed = newName != oldFolder.name
      if (renamed) {
         val newFolder = File(oldFolder.parentFile, newName)
         if (!newFolder.exists() && oldFolder.renameTo(newFolder)) {
            folder = newFolder
         } else {
            // Can't move it (e.g. a case-only rename on case-insensitive
            // storage): drop the old copy and let the sync re-download it
            // under the new name, rather than ending up with two copies.
            Filesystem(context).deleteRecursive(oldFolder)
            return Result.Success(newName, renamed = true)
         }
      }

      try {
         File(folder, Sync.RECIPE).writeText(storedJson.toString())
      } catch (e: Exception) {
         Log.w(TAG, "Could not update local recipe.json: ${e.message}")
      }
      return Result.Success(newName, renamed)
   }
}
