/*
 * JsonRecipeRepository.kt
 *
 * Copyright 2020 by MicMun
 */
package com.odiousapps.nextcloudcookbook.json

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.anggrayudi.storage.file.getAbsolutePath
import com.anggrayudi.storage.file.openInputStream
import com.odiousapps.nextcloudcookbook.db.model.DbFilesystemRecipe
import com.odiousapps.nextcloudcookbook.json.model.Recipe
import com.odiousapps.nextcloudcookbook.util.StorageManager
import com.odiousapps.nextcloudcookbook.util.json.RecipeJsonConverter
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.stream.Collectors

/**
 * Repository with the recipe data.
 *
 * @author MicMun
 * @version 2.2, 07.11.21
 */
class JsonRecipeRepository {
   companion object {
      @Volatile
      private var INSTANCE: JsonRecipeRepository? = null

      fun getInstance(): JsonRecipeRepository {
         synchronized(this) {
            var instance = INSTANCE

            if (instance == null) {
               instance = JsonRecipeRepository()
               INSTANCE = instance
            }

            return instance
         }
      }
   }

   /**
    * Reads all recipes from directory.
    */
   fun getAllRecipes(context: Context, path: String, allFileInfos: List<DbFilesystemRecipe>): List<Recipe> {
      val recipeDir = StorageManager.getDocumentFromString(context, path) ?: return emptyList()
      val recipeList = mutableListOf<Recipe>()

      if (recipeDir.exists()) {
         val subDirs = recipeDir.listFiles()

         subDirs.forEach { sd ->
            if (sd.isDirectory) {
               var jsonFile: DocumentFile? = null
               var thumbFile: DocumentFile? = null
               var fullFile: DocumentFile? = null
               val files = sd.listFiles()
               for (file in files) {
                  when (file.name) {
                     "thumb.jpg" -> thumbFile = file
                     "full.jpg" -> fullFile = file
                     "recipe.json" -> jsonFile = file
                  }
                  if (jsonFile == null && file.name?.endsWith(".json") == true) {
                     jsonFile = file
                  }
               }

               if (jsonFile != null && jsonFile.canRead()
                   && isModified(context, allFileInfos, jsonFile)) {
                  val recipe = readRecipe(context, jsonFile)

                  if (recipe != null) {
                     recipe.thumbImageUrl = thumbFile?.uri?.toString() ?: ""
                     recipe.fullImageUrl = fullFile?.uri?.toString() ?: ""

                     recipeList.add(recipe)

                     val categories = recipe.recipeCategory
                     val cats = mutableListOf<String>()
                     categories?.forEach { c ->
                        if (c.trim().isNotEmpty()) {
                           cats.add(c.trim())
                        }
                     }
                     recipe.recipeCategory = cats.toList()
                  }
               }
            }
         }
      }

      return recipeList
   }

   // check whether the file has been modified since the last scan (or is completely new)
   private fun isModified(context: Context, infos: List<DbFilesystemRecipe>, doc: DocumentFile): Boolean {
      val docAbsPath = doc.getAbsolutePath(context)
      return doc.lastModified() > (infos.find { it.filePath == docAbsPath }?.lastModified ?: 0)
   }

   /**
    * Reads a recipe and parse it to the Recipe data model.
    *
    * @param file recipe file to read.
    * @return Recipe.
    */
   private fun readRecipe(context: Context, file: DocumentFile): Recipe? {
      val inputStream = file.openInputStream(context)
      if (inputStream == null) {
         Log.e("JsonRecipeRepository", "Reading (${file.name}) failed! ")
         return null
      }

      val json: String = BufferedReader(InputStreamReader(inputStream)).use { reader ->
         reader.lines().collect(Collectors.joining("\n"))
      }

      return RecipeJsonConverter.parse(json)?.copy(
         fileLocation = file.getAbsolutePath(context),
         fileModified = file.lastModified())
   }
}
