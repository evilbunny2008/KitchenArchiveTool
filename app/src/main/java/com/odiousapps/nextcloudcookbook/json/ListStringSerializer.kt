/*
 * ListStringSerializer.kt
 *
 * Copyright 2021 by MicMun
 */
package com.odiousapps.nextcloudcookbook.json

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
      return element as? JsonPrimitive ?: if (element is JsonArray)
              if (element.jsonArray.isNotEmpty()) element.jsonArray[0] else JsonPrimitive("")
          else
              JsonPrimitive("")
   }
}
