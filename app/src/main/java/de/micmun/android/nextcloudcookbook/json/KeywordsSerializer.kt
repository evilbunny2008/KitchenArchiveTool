/*
 * KeywordsSerializer.kt
 *
 * Copyright 2021 by MicMun
 */
package de.micmun.android.nextcloudcookbook.json

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import java.util.stream.Collectors

/**
 * Serializer for Keywords.
 *
 * @author MicMun
 * @version 1.1, 28.08.21
 */
object KeywordsSerializer : JsonTransformingSerializer<List<String>>(ListSerializer(String.serializer())) {
   override fun transformDeserialize(element: JsonElement): JsonElement {
      return when (element) {
         is JsonArray -> JsonArray(element.filter { it.jsonPrimitive.content.isNotEmpty() })
         is JsonObject -> JsonArray(emptyList())
         else -> {
            val content = element.jsonPrimitive.content
            val splits: List<String> = content.split(",")
            JsonArray(splits.stream()
                         .map(String::trim)
                         .filter(String::isNotEmpty)
                         .map { k -> JsonPrimitive(k) }
                         .collect(Collectors.toList()))
         }
      }
   }
}
