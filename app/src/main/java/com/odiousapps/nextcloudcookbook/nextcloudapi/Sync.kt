package com.odiousapps.nextcloudcookbook.nextcloudapi

import android.content.Context
import android.util.Log
import com.nextcloud.android.sso.api.NextcloudAPI
import com.odiousapps.nextcloudcookbook.services.sync.SyncProgressIndicatorInterface
import com.odiousapps.nextcloudcookbook.util.Filesystem
import org.json.JSONObject
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.Executors

class Sync(mContext: Context) {

   private val mAccounts: Accounts = Accounts(mContext.applicationContext)
   private val mAPI: NextcloudAPI = mAccounts.getApiToAccount()!!
   private var mCookbookAPI: CookbookAPI = CookbookAPI(mAPI)
   private var mClosed = false
   private var mFilesystem = Filesystem(mContext)
   private var mStatusCallbacks = arrayListOf<SyncProgressIndicatorInterface>()

   companion object {
      private val TAG = Sync::class.toString()
      private const val METADATA = "METADATA"
      const val RECIPE = "recipe.json"
      const val NEW_FILE_MARKER = "NEWFILE"

      // Nextcloud Cookbook's dateModified/dateCreated are ISO 8601 date-time
      // strings (e.g. "2026-07-21T01:49:13+0000"), not integers -- confirmed
      // directly from the server's own response. The previous code read
      // this field with JSONObject.optInt(), which silently falls back to
      // its 0 default for any non-numeric value, so every single recipe
      // compared as "older than 0" and forced a full re-download on every
      // sync, regardless of whether anything had actually changed. This
      // was misattributed to an "upstream API inconsistency" (see the old
      // comment below, now corrected) -- it was a client-side parsing bug.
      private val DATE_MODIFIED_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")
   }

   /**
    * Parses a Nextcloud Cookbook dateModified/dateCreated string (ISO 8601,
    * e.g. "2026-07-21T01:49:13+0000") into epoch seconds, for comparison.
    * Returns 0 if the field is missing or unparseable, matching the
    * previous code's "treat as very old / force re-download" fallback for
    * that case.
    */
   private fun parseDateModified(json: JSONObject): Long {
      val raw = json.optString("dateModified", "")
      if (raw.isEmpty()) return 0L
      return try {
         OffsetDateTime.parse(raw, DATE_MODIFIED_FORMATTER).toEpochSecond()
      } catch (_: DateTimeParseException) {
         try {
            // fall back to the standard colon-offset ISO format (e.g.
            // "+00:00" instead of "+0000"), in case a different server
            // version formats it that way
            OffsetDateTime.parse(raw).toEpochSecond()
         } catch (_: DateTimeParseException) {
            0L
         }
      }
   }

   /**
    * This function calls downloadRecipes() on the main thread.
    */
   fun synchronizeRecipesAsync() {
      if (mClosed) {
         throw ApiClosedException("The Api has already been closed. Please reinstantiate this class!")
      }

      Executors.newSingleThreadExecutor().submit {
         synchronizeRecipes()
      }
   }

   /**
    * This function starts a bidirectional sync with the cookbook api.
    * It requires that the api is not closed.
    *
    */
   fun synchronizeRecipes() {

      if (mClosed) {
         throw ApiClosedException("The Api has already been closed. Please reinstantiate this class!")
      }

      mCookbookAPI = CookbookAPI(mAccounts.getApiToAccount()!!)
      val remoteList = mCookbookAPI.getRecipes()
      val recipeIds = ArrayList<String>()
      var i = 1
      for (recipe in remoteList) {
         val recipeMetadata = JSONObject(recipe)
         val recipeId = recipeMetadata.getString("recipe_id")
         val name = recipeMetadata.getString("name")
         Log.d(TAG, "Pulling Recipe: $name")
         mStatusCallbacks.forEach {
            it.updateProgress(i++, remoteList.size, name)
         }

         // dateModified is an ISO 8601 string, not an integer -- see
         // parseDateModified()'s doc comment for why this matters.
         val dateRemote = parseDateModified(recipeMetadata)
         val dateLocal = parseDateModified(readMetadata(name))

         // Todo: This breaks when both local and remote recipe have changed.
         //       The last one changed will be used.
         //       We need to think about if we want that. (When we implement editing)
         if (dateRemote > dateLocal || dateRemote == 0L) {
            Log.d(TAG, "Local Recipe out of date: $name")
            try {
               downloadRecipe(recipeMetadata)
            } catch (e: Exception) {
               e.printStackTrace()
               Log.e(TAG, "Error pulling recipe: ${e.message}")
            }
         } else if (dateRemote < dateLocal) {
            Log.w(TAG, "Remote Recipe out of date: $name")
            //update file on the remote
         } else {
            Log.d(TAG, "The Recipe is unchanged. Not syncing.")
         }
         recipeIds.add(recipeId)
      }
      cleanOldRecipes(recipeIds)
      addNewRecipes()
      closeAPI()
   }

   /**
    * Uploads recipes that have the NEW_FILE_MARKER file present.
    * Only works on the currently selected useraccount
    */
   private fun addNewRecipes() {
      val username = mAccounts.getCurrentAccount()!!.name
      val externalDir = mFilesystem.getInternalStoragePath()
      val folders = File(externalDir, "recipes/$username/")
      val results = folders.listFiles()
      if (results != null) {
         for (folder in results) {
            if (File(folder, NEW_FILE_MARKER).exists()) {
               pushJsonToRemote(folder)
            }
         }
      }
   }

   /**
    * Uploads a recipe.
    * If a recipe already exists, it will retry with (Copy) appended to the recipe name.
    * It moves the recipe to the appropriate folder if (Copy) was appended.
    * Todo: Move Folder if (Copy) appended
    */
   private fun pushJsonToRemote(folder: File, recipeNameAppendix: String = "") {
      val json = JSONObject(mFilesystem.readInternalFile(File(folder, RECIPE)))
      json.put("name", json.get("name$recipeNameAppendix"))
      val id = mCookbookAPI.createRecipe(json)
      if (id != null) {
         json.put("id", id.toInt())
         mFilesystem.writeDataToInternal(
            "recipes/${folder.name}/${getUsername()}/",
            RECIPE,
            json.toString().toByteArray()
         )

         // this seems tedious, but I think the api is not quite ready.
         val remoteList = mCookbookAPI.getRecipes()
         for (recipe in remoteList) {
            val recipeId = JSONObject(recipe).getString("recipe_id")
            if (recipeId == id) {
               mFilesystem.writeDataToInternal(
                  "recipes/${getUsername()}/${folder.name}/",
                  METADATA,
                  JSONObject(recipe).toString().toByteArray()
               )
            }
         }
         File(folder, NEW_FILE_MARKER).delete()
      } else {
         Log.w(TAG, "Upload failed! Recipe probably already exists!")
         pushJsonToRemote(folder, "(Copy)")
      }
   }

   /**
    * If a recipe is no longer available on the server, it will be deleted from the filesystem locally.
    *
    * Todo: Think about moving the recipe to a different "DELETED" folder, to allow the user to restore it.
    */
   private fun cleanOldRecipes(recipes: ArrayList<String>) {
      Log.d(TAG, "Clean old recipes")
      val username = mAccounts.getCurrentAccount()!!.name
      val externalDir = mFilesystem.getInternalStoragePath()
      val folders = File(externalDir, "recipes/$username/")
      val results = folders.listFiles()
      if (results != null) {
         for (folder in results) {
            if (!File(folder, NEW_FILE_MARKER).exists()) {
               val metadataContent = mFilesystem.readInternalFile(File(folder, METADATA))
               if (metadataContent.isNotEmpty()) {
                  val id = JSONObject(metadataContent).getString("recipe_id")
                  if (!recipes.contains(id)) {
                     Log.d(TAG, "This recipe is to be deleted: ${folder.absolutePath}")
                     mFilesystem.deleteRecursive(folder)
                  }
               } else {
                  Log.w(TAG, "Metadatafile empty while cleaning old recipes!")
               }
            }
         }
      }
   }

   /**
    * Downloads a singular recipe from the remote api. The id is extracted from the
    * recipeMetadata-Json-Object. After that, the appropritate folder is created,
    * and all files downloaded to it.
    */
   private fun downloadRecipe(recipeMetadata: JSONObject) {
      val recipeId = recipeMetadata.getString("recipe_id")
      val name = recipeMetadata.getString("name")

      val recipe = mCookbookAPI.getRecipe(recipeId)
      val username = mAccounts.getCurrentAccount()!!.name

      // use known name, recipe.json, to find recipe again when changes have been made.
      mFilesystem.writeDataToInternal("recipes/$username/$name/", RECIPE, recipe.toByteArray())
      mFilesystem.writeDataToInternal("recipes/$username/$name/", METADATA, recipeMetadata.toString().toByteArray())

      val sizes = arrayOf("thumb", "thumb16", "full")
      for (size in sizes) {
         try {
            val bytes = mCookbookAPI.getImage(recipeId, size)
            if (bytes != null) {
               mFilesystem.writeDataToInternal("recipes/$username/$name/", "$size.jpg", bytes)
            }
         } catch (e: Exception) {
            Log.e(TAG, "Error pulling image - $size: ${e.message}")
         }
      }
   }

   private fun getUsername(): String {
      if (mAccounts.getCurrentAccount() == null) {
         Log.e(TAG, "There is no account, cannot create directory!")
         return "local"
      }
      return mAccounts.getCurrentAccount()!!.name

   }

   private fun readMetadata(name: String): JSONObject {
      val username = mAccounts.getCurrentAccount()!!.name
      val externalDir = mFilesystem.getInternalStoragePath()
      val file = File(externalDir, "recipes/$username/$name/$METADATA")
      val json = mFilesystem.readInternalFile(file)
      if (json == "") {
         return JSONObject()
      }
      return JSONObject(json)
   }

   fun closeAPI() {
      mClosed = true
      mAPI.close()
   }

   fun registerUpdateCallback(callback: SyncProgressIndicatorInterface) {
      mStatusCallbacks.add(callback)
   }
}