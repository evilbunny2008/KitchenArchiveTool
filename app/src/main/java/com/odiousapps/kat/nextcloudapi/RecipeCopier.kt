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
import com.nextcloud.android.sso.model.SingleSignOnAccount
import org.json.JSONObject
import java.io.File

/**
 * Copies a single recipe's data, including its photo, from the recipe
 * this app already has cached locally to a *different* Nextcloud
 * account's Cookbook instance.
 *
 * ## Why the photo needs a two-hop upload
 *
 * Cookbook's server-side recipe-create code (RecipeService::addRecipe)
 * treats the "image" field two different ways:
 * - If it looks like a URL (starts with "http"), the *server* fetches it
 *   itself over plain HTTP, with no authentication and with SSRF
 *   protection that deliberately blocks local/internal addresses.
 * - Otherwise, it's read as a path relative to the *current* user's own
 *   Nextcloud files -- read directly off disk, no network fetch at all.
 *
 * The first mode is fundamentally unusable for a cross-account copy: the
 * destination server has no way to authenticate to the source server (this
 * app's SSO credentials aren't something a server-side HTTP request on a
 * different Nextcloud instance can present), so the fetch gets a 401/login
 * page instead of the image -- and even if that weren't true, two
 * self-hosted instances are often on the same local network, which the
 * SSRF guard blocks outright.
 *
 * So this class downloads the photo (already-working, authenticated,
 * same as normal sync) from the *source* account, re-uploads those same
 * bytes into the *destination* account's own storage via WebDAV, and
 * points "image" at that local path instead -- same-account, same-server,
 * no fetch and no auth problem. The temp upload is deleted again once the
 * recipe has been created (best-effort; Cookbook copies the bytes into
 * the recipe's own folder immediately, so the temp file is never needed
 * again either way).
 *
 * A missing or failed photo never blocks the rest of the recipe from
 * copying -- "image" is simply left out of the payload in that case.
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

      /**
       * Where the photo is temporarily staged in the destination
       * account's own files while the recipe is being created. Named
       * per-source-recipe to avoid clobbering an unrelated file of the
       * same name if two copies happen to run close together.
       */
      private fun tempImagePath(sourceRecipeId: String) = "/.kat-copy-temp-$sourceRecipeId.jpg"
   }

   sealed class Result {
      data class Success(val newRecipeId: String) : Result()
      data class Failure(val reason: String) : Result()
   }

   /**
    * @param recipeJsonFile the local recipe.json this recipe was last synced from
    *   (DbRecipe.recipeCore.fileSystem.filePath) -- its sibling METADATA file
    *   (see Sync.kt) is also read, to find the source recipe's server-side id
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
      // Replaced below with a local-path reference if a photo is actually
      // staged on the destination; left out of the payload entirely otherwise.
      json.remove("image")

      val destinationSsoAccount = try {
         AccountImporter.getSingleSignOnAccount(context, destinationAccountName)
      } catch (e: Exception) {
         Log.e(TAG, "Could not open destination account: ${e.message}")
         return Result.Failure("Could not access the destination account")
      }

      val destinationApi = try {
         NextcloudAPI(context, destinationSsoAccount, GsonBuilder().create())
      } catch (e: Exception) {
         Log.e(TAG, "Could not open destination account: ${e.message}")
         return Result.Failure("Could not access the destination account")
      }

      val stagedImagePath = stagePhotoOnDestination(recipeJsonFile, destinationApi, destinationSsoAccount)
      stagedImagePath?.let { json.put("image", it) }

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
         stagedImagePath?.let { WebDavClient(destinationApi, destinationSsoAccount).deleteFile(it) }
         destinationApi.close()
      }
   }

   /**
    * Downloads the recipe's photo from the source account and uploads it
    * into the destination account's own storage at a temporary path,
    * returning that path for use as the recipe's "image" field. Returns
    * null (skip the photo, don't block the rest of the copy) if there's
    * no source photo, no source account, or any step fails.
    */
   private fun stagePhotoOnDestination(
      recipeJsonFile: File,
      destinationApi: NextcloudAPI,
      destinationSsoAccount: SingleSignOnAccount
   ): String? {
      val metadataFile = File(recipeJsonFile.parentFile, Sync.METADATA)
      if (!metadataFile.exists()) return null

      val sourceRecipeId = try {
         JSONObject(metadataFile.readText()).getString("recipe_id")
      } catch (e: Exception) {
         Log.w(TAG, "Could not read source recipe id from METADATA: ${e.message}")
         return null
      }

      val sourceApi = Accounts(context).getApiToAccount() ?: return null
      val imageBytes = try {
         CookbookAPI(sourceApi).getImage(sourceRecipeId, "full")
      } finally {
         sourceApi.close()
      } ?: return null

      val path = tempImagePath(sourceRecipeId)
      val uploaded = WebDavClient(destinationApi, destinationSsoAccount).uploadFile(path, imageBytes)
      return if (uploaded) path else null
   }
}
