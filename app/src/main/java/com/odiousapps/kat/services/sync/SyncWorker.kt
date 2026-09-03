/*
 * SyncWorker.kt
 *
 * Copyright 2026 by MicMun
 */
package com.odiousapps.kat.services.sync

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.odiousapps.kat.nextcloudapi.Sync
import com.odiousapps.kat.settings.PreferenceData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs a single recipe sync, triggered either by [SyncScheduler]'s
 * periodic schedule or by a manual pull-to-refresh.
 *
 * Replaces the previous SyncService (a foreground Service). This work
 * doesn't need a persistent "syncing..." notification the way a music
 * player or an active navigation session does -- it's a quick
 * check-and-download the person isn't necessarily watching happen, which
 * is exactly the kind of task WorkManager exists for, and doing it this
 * way sidesteps Android's foreground-service permission requirements
 * entirely (see SyncScheduler's doc comment for the fuller reasoning).
 *
 * @author MicMun
 * @version 1.0, 04.09.26
 */
class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

   companion object {
      private val TAG = SyncWorker::class.toString()

      // Broadcast contract kept identical to the previous SyncService's,
      // so RecipeListFragment/LocalBroadcastReceiver's existing "spinner
      // on while syncing" wiring didn't need to change.
      const val SYNC_UPDATE_BROADCAST = "SYNC_SERVICE_UPDATE_BROADCAST"
      const val SYNC_UPDATE_STATUS = "SYNC_SERVICE_UPDATE_STATUS"
      const val SYNC_UPDATE_STATUS_START = "SYNC_SERVICE_UPDATE_STATUS_START"
      const val SYNC_UPDATE_STATUS_END = "SYNC_SERVICE_UPDATE_STATUS_END"
   }

   override suspend fun doWork(): Result {
      sendStatus(SYNC_UPDATE_STATUS_START)
      return try {
         withContext(Dispatchers.IO) {
            val sync = Sync(applicationContext)
            try {
               sync.synchronizeRecipes()
            } finally {
               sync.closeAPI()
            }
         }
         PreferenceData.getInstance().setStorageAccessed(true)
         Result.success()
      } catch (e: Exception) {
         // Matches the previous SyncService's behaviour: log and stop,
         // rather than WorkManager's default retry-with-backoff -- a
         // failed sync (e.g. no account configured, or a transient
         // network error) will simply get picked up by the next
         // scheduled run or manual pull-to-refresh instead of retrying
         // in a tight loop.
         Log.e(TAG, "Error syncing: ${e.message}")
         Result.failure()
      } finally {
         sendStatus(SYNC_UPDATE_STATUS_END)
      }
   }

   private fun sendStatus(status: String) {
      val intent = Intent(SYNC_UPDATE_BROADCAST).putExtra(SYNC_UPDATE_STATUS, status)
      LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
   }
}
