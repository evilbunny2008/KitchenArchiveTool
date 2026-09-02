/*
 * StringListSerializer.kt
 *
 * Copyright 2021 by MicMun
 */
package de.micmun.android.nextcloudcookbook.json

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer

/**
 * Serializer for wrap a string into a list.
 *
 * @author MicMun
 * @version 1.0, 09.07.21
 */
object StringListSerializer : JsonTransformingSerializer<List<String>>(ListSerializer(String.serializer())) {
   override fun transformDeserialize(element: JsonElement): JsonElement {
      return when (element) {
         is JsonObject -> JsonArray(emptyList())
         is JsonArray -> element
         else -> JsonArray(listOf(element))
      }
   }
}
