/*
 * RecipeJsonConverter.kt
 *
 * Copyright 2021 by Leafar
 */
package de.micmun.android.nextcloudcookbook.util.json

import android.util.Log
import de.micmun.android.nextcloudcookbook.json.model.Recipe
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

/**
 * Convert between Recipe objects and their json representation.
 *
 * @author MicMun
 * @version 1.2, 11.08.21
 */
class RecipeJsonConverter {
   companion object {
      fun write(recipe: Recipe): String {
         return getParser().encodeToString(Recipe.serializer(), recipe)
      }

      fun parse(json: String): Recipe? {
         return try {
            getParser().decodeFromString(Recipe.serializer(), json)
         } catch (e: SerializationException) {
            Log.e("RecipeJsonConverter", "SerializationException: ${e.message} for json = {$json}")
            null
         } catch (e: Exception) {
            Log.e("RecipeJsonConverter", "Exception: ${e.message} for json = {$json}")
            e.printStackTrace()
            null
         }
      }

      fun parse(json: JsonObject): Recipe? {
         return try {
            getParser().decodeFromJsonElement(Recipe.serializer(), json)
         } catch (e: SerializationException) {
            null
         } catch (e: IllegalArgumentException) {
            null
         }
      }

      fun parseFromWeb(json: String): JsonObject? {
         // websites may provide multiple ld-json in one script tag as an array
         try {
            val jsArray = getParser().parseToJsonElement(json).jsonArray

            for (obj in jsArray) {
               // could also check js["@context"] == "http://schema.org"
               if (obj is JsonObject && obj.jsonObject["@type"]?.jsonPrimitive?.content ?: "" == "Recipe") {
                  return obj
               }
            }
            return null
         } catch (e: Exception) {
         }

         // others provide a root object with an "@graph" array
         try {
            val graph = getParser().parseToJsonElement(json).jsonObject["@graph"]
            val arr = graph?.jsonArray
            arr?.let { jsArray ->
               for (obj in jsArray) {
                  if (obj is JsonObject && obj.jsonObject["@type"]?.jsonPrimitive?.content ?: "" == "Recipe") {
                     return obj
                  }
               }
               return null
            }
         } catch (e: Exception) {
         }

         val jsonObject = getParser().parseToJsonElement(json).jsonObject
         if (jsonObject["@type"]?.jsonPrimitive?.content ?: "" == "Recipe") {
            return jsonObject
         }
         return null
      }

      private fun getParser(): Json {
         return Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
         }
      }
   }
}