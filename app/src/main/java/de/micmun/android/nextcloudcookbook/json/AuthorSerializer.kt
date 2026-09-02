/*
 * AuthorSerializer.kt
 *
 * Copyright 2021 by MicMun
 */
package de.micmun.android.nextcloudcookbook.json

import de.micmun.android.nextcloudcookbook.json.model.Author
import kotlinx.serialization.json.*

/**
 * Serializer for author.
 *
 * @author MicMun
 * @version 1.0, 09.07.21
 */
object AuthorSerializer : JsonTransformingSerializer<Author>(Author.serializer()) {
   override fun transformDeserialize(element: JsonElement): JsonElement {
      return when (element) {
         is JsonPrimitive -> JsonObject(mapOf(Pair("name", JsonPrimitive(element.content))))
         is JsonArray -> JsonObject(emptyMap())
         else -> element
      }
   }
}
