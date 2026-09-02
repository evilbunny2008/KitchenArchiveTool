/*
 * NutritionSerializer.kt
 *
 * Copyright 2021 by MicMun
 */
package de.micmun.android.nextcloudcookbook.json

import de.micmun.android.nextcloudcookbook.json.model.Nutrition
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer

/**
 * Serializer for nutritions.
 *
 * @author MicMun
 * @version 1.0, 09.07.21
 */
object NutritionSerializer : JsonTransformingSerializer<Nutrition>(Nutrition.serializer()) {
   override fun transformDeserialize(element: JsonElement): JsonElement {
      return if (element !is JsonObject)
         JsonObject(emptyMap())
      else element
   }
}
