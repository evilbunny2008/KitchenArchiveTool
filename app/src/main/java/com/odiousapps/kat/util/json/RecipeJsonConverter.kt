/*
 * RecipeJsonConverter.kt
 *
 * Copyright 2021 by Leafar
 */
package com.odiousapps.kat.util.json

import android.util.Log
import com.odiousapps.kat.json.model.Recipe
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

/**
 * Convert between Recipe objects and their JSON representation.
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
         } catch (_: SerializationException) {
            null
         } catch (_: IllegalArgumentException) {
            null
         }
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
