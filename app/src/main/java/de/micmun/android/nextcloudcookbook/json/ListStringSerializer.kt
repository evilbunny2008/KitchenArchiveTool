/*
 * ListStringSerializer.kt
 *
 * Copyright 2021 by MicMun
 */
package de.micmun.android.nextcloudcookbook.json

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*

/**
 * Serializier for a string object.
 *
 * @author MicMun
 * @version 1.0, 09.07.21
 */
object ListStringSerializer : JsonTransformingSerializer<String>(String.serializer()) {
   override fun transformDeserialize(element: JsonElement): JsonElement {
      return if (element is JsonPrimitive)
         element
      else if (element is JsonArray)
         if (element.jsonArray.size > 0) element.jsonArray[0] else JsonPrimitive("")
      else {
         JsonPrimitive("")
      }
   }
}
