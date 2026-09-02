/*
 * ImageSerializier.kt
 *
 * Copyright 2021 by MicMun
 */
package de.micmun.android.nextcloudcookbook.json

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*

/**
 * Serializer for image url.
 *
 * @author MicMun
 * @version 1.1, 29.08.21
 */
object ImageSerializier : JsonTransformingSerializer<String>(String.serializer()) {
   override fun transformDeserialize(element: JsonElement): JsonElement {
      return when (element) {
         is JsonArray -> if (element.jsonArray.size == 0) JsonPrimitive("") else element.jsonArray[0]
         is JsonPrimitive -> element
         else -> {
            if (element.jsonObject.containsKey("url"))
               element.jsonObject["url"] ?: JsonPrimitive("")
            else
               JsonPrimitive("")
         }
      }
   }
}
