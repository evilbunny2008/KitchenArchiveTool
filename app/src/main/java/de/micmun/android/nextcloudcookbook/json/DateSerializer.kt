/*
 * DateSerializer.kt
 *
 * Copyright 2021 by MicMun
 */
package de.micmun.android.nextcloudcookbook.json

import android.util.Log
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.time.*
import java.time.format.DateTimeParseException
import java.util.*

/**
 * Serializer for dates.
 *
 * @author MicMun
 * @version 1.0, 09.07.21
 */
object DateSerializer : JsonTransformingSerializer<String>(String.serializer()) {
   private val sdf = SimpleDateFormat.getDateInstance()

   override fun transformDeserialize(element: JsonElement): JsonElement {
      return if (element !is JsonPrimitive)
         JsonPrimitive("")
      else {
         val dateStr = element.content
         val fixed = fixDate(dateStr)
         val date = parseIso8861(fixed)
         var ldate = ""

         if (date != null) {
            ldate = sdf.format(Date(date.toInstant(ZoneOffset.UTC).toEpochMilli()))
         } else {
            try {
               val time = fixed.toLong()
               ldate = sdf.format(Date(time))
            } catch (e1: NumberFormatException) {
               Log.e("DateSerializer", "NumberFormatException: ${e1.localizedMessage}")
            } catch (e2: DateTimeException) {
               Log.e("DateSerializer", "DateTimeException: ${e2.localizedMessage}")
            }
         }

         JsonPrimitive(ldate)
      }
   }

   override fun transformSerialize(element: JsonElement): JsonElement {
      return try {
         val date = sdf.parse(element.jsonPrimitive.content)
         if (date != null) JsonPrimitive(toJsonImpl(date)) else JsonPrimitive("")
      } catch (e: IllegalArgumentException) {
         JsonPrimitive("")
      }
   }

   private fun fixDate(str: String): String {
      var date = str
      val pointPos = date.indexOf(".")
      if (pointPos != -1)
         date = date.substring(0, pointPos)
      val plusPos = date.indexOf("+")
      if (plusPos != -1)
         date = date.substring(0, plusPos)
      val zPos = date.indexOf("Z")
      if (zPos != -1)
         date = date.substring(0, zPos)

      date = date.replace(' ', 'T')
      return date
   }

   private fun parseIso8861(dateString: String): LocalDateTime? {
      return try {
         val date = LocalDate.parse(dateString)
         LocalDateTime.of(date, LocalTime.MIN)
      } catch (e: DateTimeParseException) {
         try {
            LocalDateTime.parse(dateString)
         } catch (e1: DateTimeParseException) {
            null
         }
      }
   }

   private fun toJsonImpl(date: Date): String {
      val ldt = LocalDateTime.ofEpochSecond(date.time / 1000, 0, ZoneOffset.UTC)
      return "\"${ldt}\""
   }
}
