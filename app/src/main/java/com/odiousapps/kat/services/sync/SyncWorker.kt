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
import androidx.work.workDataOf
import com.odiousapps.kat.nextcloudapi.Sync
import com.odiousapps.kat.notifications.SyncNotificationHelper
import com.odiousapps.kat.settings.PreferenceData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs a single recipe sync, triggered either by [SyncScheduler]'s
 * periodic schedule or by a manual pull-to-refresh (or the initial sync
 * right after logging in).
 *
 * Replaces the previous SyncService (a foreground Service). This work
 * doesn't need a persistent "syncing..." notification the way a music
 * player or an active navigation session does -- it's a quick
 * check-and-download the person isn't necessarily watching happen, which
 * is exactly the kind of task WorkManager exists for, and doing it this
 * way sidesteps Android's foreground-service permission requirements
 * entirely (see SyncScheduler's doc comment for the fuller reasoning).
 *
 * Per-recipe progress (Sync's own SyncProgressIndicatorInterface, see
 * registerUpdateCallback() below) is always published via setProgress(),
 * so any screen that's actively observing this work (see
 * SyncScheduler.observeManualSyncState()) can show it live -- e.g. a
 * progress dialog. For a periodic/background sync specifically ([KEY_IS_MANUAL_SYNC]
 * false), the same progress is also shown in a notification, since
 * nothing is necessarily watching a WorkInfo LiveData for one of those.
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

      /** Input data key: true for a manually-triggered sync (login, pull-to-refresh), false for a periodic/background one. */
      const val KEY_IS_MANUAL_SYNC = "is_manual_sync"

      // Progress Data keys -- read back via WorkInfo.progress by anything
      // observing SyncScheduler.observeManualSyncState() (e.g. a progress dialog).
      const val PROGRESS_ITEM = "progress_item"
      const val PROGRESS_OVERALL = "progress_overall"
      const val PROGRESS_TITLE = "progress_title"
   }

   override suspend fun doWork(): Result {
      val isManualSync = inputData.getBoolean(KEY_IS_MANUAL_SYNC, false)
      sendStatus(SYNC_UPDATE_STATUS_START)
      return try {
         withContext(Dispatchers.IO) {
            val sync = Sync(applicationContext)
            sync.registerUpdateCallback { item, overall, title ->
               // setProgressAsync(), not the suspend setProgress(): this
               // callback comes from Sync's own (non-suspend) interface,
               // so there's no suspend context available to call into
               // here. Fire-and-forget is fine for progress updates --
               // occasionally racing/overwriting one isn't a problem.
               setProgressAsync(workDataOf(PROGRESS_ITEM to item, PROGRESS_OVERALL to overall, PROGRESS_TITLE to title))
               if (!isManualSync) {
                  SyncNotificationHelper.showProgress(applicationContext, item, overall, title)
               }
            }
            try {
               sync.synchronizeRecipes()
            } finally {
               sync.closeAPI()
            }
         }
         PreferenceData.getInstance().setStorageAccessed(true)
         PreferenceData.getInstance().setLastSyncCompletedAt(System.currentTimeMillis())
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
         if (!isManualSync) {
            SyncNotificationHelper.clear(applicationContext)
         }
      }
   }

   private fun sendStatus(status: String) {
      val intent = Intent(SYNC_UPDATE_BROADCAST).putExtra(SYNC_UPDATE_STATUS, status)
      LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
   }
}
