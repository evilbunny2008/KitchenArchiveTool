/*
 * CookTimer.kt
 *
 * Copyright 2021 by MicMun
 */
package de.micmun.android.nextcloudcookbook.util

import android.os.CountDownTimer

/**
 * Cooking timer countdown.
 *
 * @author MicMun
 * @version 1.0, 24.07.21
 */
class CookTimer(timeInMillis: Long, private val listener: CookTimeListener) : CountDownTimer(timeInMillis, 1000) {
   var remains: Long = timeInMillis

   override fun onTick(millisUntilFinished: Long) {
      remains = millisUntilFinished
      listener.refreshOnTick(remains)
   }

   override fun onFinish() {
      remains = 0
      listener.refreshOnFinish()
   }

   interface CookTimeListener {
      /**
       * Called when a time unit is passed with current remaining milliseconds.
       *
       * @param remains remaining milliseconds.
       */
      fun refreshOnTick(remains: Long)

      /**
       * Called when timer is finished.
       */
      fun refreshOnFinish()
   }
}
