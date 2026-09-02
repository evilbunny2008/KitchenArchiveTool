package de.micmun.android.nextcloudcookbook.json

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*

object InstructionSerializer : JsonTransformingSerializer<List<String>>(ListSerializer(InstructionStepSerializer)) {
   override fun transformDeserialize(element: JsonElement): JsonElement {
      return if (element is JsonArray)
         element
      else {
         require(element is JsonPrimitive)
         JsonArray(listOf(element))
      }
   }
}

object InstructionStepSerializer : JsonTransformingSerializer<String>(String.serializer()) {
   override fun transformDeserialize(element: JsonElement): JsonElement {
      if (element is JsonPrimitive) {
         require(element.jsonPrimitive.isString)
         return element
      }
      if (element is JsonObject) {
         val type = element.jsonObject["@type"]?.toString()
         require("\"HowToStep\"" == type) { "Unknown type '$type'" }
         val text = element.jsonObject["text"]
         require(text != null) { "Expected text element in HowToStep" }
         return text
      }
      require(false) { "Unknown json type for instruction step:\n$element" }
      return JsonPrimitive("")
   }
}
