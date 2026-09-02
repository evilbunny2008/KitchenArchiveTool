/*
 * DurationUtils.kt
 *
 * Copyright 2020 by MicMun
 */
package de.micmun.android.nextcloudcookbook.util

import android.content.Context
import android.os.Build
import android.text.format.DateFormat
import androidx.annotation.RequiresApi
import java.time.Duration
import java.util.*

/**
 * Utilities for the duration format.
 *
 * @author MicMun
 * @version 1.4, 24.07.21
 */
class DurationUtils {
   companion object {
      /**
       * Returns the better formatted String from the iso 8601 format.
       *
       * @param isoString Duration in iso 8601 format.
       * @return formatted String to display.
       */
      fun formatStringToDuration(isoString: String): String {
         return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getDisplayString(isoString)
         } else {
            getLegacyDisplayString(isoString)
         }
      }

      /**
       * Returns the minutes from duration.
       *
       * @param isoString Duration in iso 8601 format.
       * @return Duration in minutes.
       */
      fun durationInMinutes(isoString: String?): Int {
         if (isoString.isNullOrEmpty())
            return 0
         val formatedDuration = formatStringToDuration(isoString)
         val hours = formatedDuration.substring(0, 2).toInt()
         val minutes = formatedDuration.substring(3, 5).toInt()

         return hours * 60 + minutes
      }

      fun formatDate(context: Context, publishedDate: Date): String {
         return DateFormat.getDateFormat(context).format(publishedDate)
      }

      /**
       * Returns the number of seconds from display duration.
       *
       * @param isoString Duration in display format.
       * @return Number of seconds.
       */
      fun durationInSeconds(isoString: String): Long {
         val duration = Duration.parse(isoString) ?: Duration.ZERO
         return duration.seconds
      }

      /**
       * Returns the formatted duration for display in the timer.
       *
       * @param time Number of seconds.
       * @return formatted duration in format HH:MI:SS.
       */
      fun formatDurationSeconds(time: Long): String {
         val hours = time / 3600
         var minutes = time % 3600
         val second = minutes % 60
         minutes /= 60

         return String.format("%02d:%02d:%02d", hours, minutes, second)
      }

      /**
       * Returns the formatted string from the iso 8601 string (>= API 26).
       *
       * @param isoString Duration in iso 8601 format.
       * @return formatted string.
       */
      @RequiresApi(Build.VERSION_CODES.O)
      private fun getDisplayString(isoString: String): String {
         val duration = Duration.parse(isoString)
         var minutes = duration.toMinutes()
         var hours = 0

         while (minutes >= 60) {
            hours += 1
            minutes -= 60
         }

         return String.format(Locale.getDefault(), "%02d:%02d", hours, minutes)
      }

      /**
       * Returns the formatted string from the iso 8601 string (< API 26).
       *
       * @param isoString Duration in iso 8601 format.
       * @return formatted string.
       */
      private fun getLegacyDisplayString(isoString: String): String {
         val start = isoString.indexOf("T") + 1
         val reduced = isoString.substring(start, isoString.length)
         val hourIndex = reduced.indexOf("H")
         val minuteIndex = reduced.indexOf("M")
         var hours = 0
         var minutes = 0

         if (hourIndex != -1) {
            hours = reduced.substring(0, hourIndex).toInt()
         }
         if (minuteIndex != -1) {
            minutes = reduced.substring(if (hourIndex != -1) hourIndex + 1 else 0, minuteIndex).toInt()
         }

         return String.format(Locale.getDefault(), "%02d:%02d", hours, minutes)
      }
   }
}
