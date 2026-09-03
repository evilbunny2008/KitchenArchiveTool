/*
 * DurationUtils.kt
 *
 * Copyright 2020 by MicMun
 */
package com.odiousapps.kat.util

import java.time.Duration
import java.util.Locale

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
         return getDisplayString(isoString)
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

         return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, second)
      }

      /**
       * Returns the formatted string from the iso 8601 string (>= API 26).
       *
       * @param isoString Duration in iso 8601 format.
       * @return formatted string.
       */
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
   }
}
