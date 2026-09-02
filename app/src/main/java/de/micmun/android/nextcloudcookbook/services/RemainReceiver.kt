/*
 * RemainReceiver.kt
 *
 * Copyright 2021 by MicMun
 */
package de.micmun.android.nextcloudcookbook.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * BroadcastReceiver for CooktimeService.
 *
 * @author MicMun
 * @version 1.0, 28.08.21
 */
class RemainReceiver : BroadcastReceiver() {
   companion object {
      /**
       * ACTION Constant for sending remaining time.
       */
      const val REMAIN_ACTION = "send_remaining_time"
      const val KEY_REMAINS = "REMAINING"
   }

   var remains: Long? = null

   override fun onReceive(context: Context?, intent: Intent?) {
      intent?.let {
         val action = it.action
         val hasRemains = it.extras?.containsKey(KEY_REMAINS) ?: false
         val value = it.extras?.getLong(KEY_REMAINS) ?: 0L

         if (action == REMAIN_ACTION && hasRemains) {
            remains = value
         }
      }
   }
}
