/*
 * SyncScheduler.kt
 *
 * Copyright 2026 by MicMun
 */
package com.odiousapps.kat.services.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.odiousapps.kat.nextcloudapi.Accounts
import com.odiousapps.kat.settings.PreferenceData
import java.util.concurrent.TimeUnit

/**
 * Schedules and triggers recipe sync via WorkManager.
 *
 * This replaces the previous approach (a foreground Service armed by a
 * raw AlarmManager repeating alarm, re-armed on every boot via a
 * BOOT_COMPLETED receiver). That approach is what crashed: calling
 * startForeground() requires the base android.permission.FOREGROUND_SERVICE
 * permission in addition to the type-specific one (FOREGROUND_SERVICE_DATA_SYNC),
 * and a recipe sync -- a quick background check-and-download the person
 * isn't necessarily watching happen -- doesn't really fit what foreground
 * services are meant for (an ongoing task the person is actively aware
 * of, like music playback). WorkManager is the platform-recommended
 * replacement for exactly this kind of periodic background sync:
 * - No foreground-service permission or persistent notification needed.
 * - Automatically respects Doze/battery-optimization windows.
 * - The wifi-only setting becomes a real NetworkType constraint instead
 *   of a manual runtime check.
 * - Periodic work survives reboots on its own -- no BOOT_COMPLETED
 *   receiver needed to re-arm anything (unlike the previous AlarmManager
 *   approach, which is why BootReceiver was removed rather than updated).
 *
 * There was also a latent bug in the previous scheduling path worth
 * noting: the AlarmManager's PendingIntent targeted LocalBroadcastReceiver
 * as a manifest-registered (not LocalBroadcastManager-registered)
 * receiver, so Android instantiated a fresh instance with no
 * RecipeListFragment reference on every alarm firing -- meaning the
 * scheduled background sync's "start" broadcast was calling
 * `mRecipeFragment?.onRefresh()` on a value that was always null. Background
 * sync was silently a no-op before this migration, regardless of today's
 * permission crash.
 *
 * @author MicMun
 * @version 1.0, 04.09.26
 */
object SyncScheduler {
   private const val PERIODIC_WORK_NAME = "recipe_sync_periodic"
   private const val MANUAL_WORK_NAME = "recipe_sync_manual"

   const val SYNC_SERVICE_INTERVAL_DEFAULT = 24
   const val SYNC_SERVICE_WIFI_ONLY_DEFAULT = true

   /**
    * (Re)applies the periodic schedule from current preferences. Safe to
    * call any time settings that affect it change (interval, wifi-only)
    * or on app startup: ExistingPeriodicWorkPolicy.UPDATE means this is
    * effectively a no-op if nothing actually changed, and cleanly
    * replaces the schedule (new interval/constraints take effect from
    * the next run) if something did -- no manual cancel-then-reschedule
    * dance required, unlike the old AlarmManager approach.
    */
   fun reschedule(context: Context) {
      val appContext = context.applicationContext
      val workManager = WorkManager.getInstance(appContext)

      if (Accounts(appContext).getCurrentAccount() == null || !PreferenceData.getInstance().isSyncServiceEnabled()) {
         workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
         return
      }

      val intervalHours = PreferenceData.getInstance().getSyncServiceInterval().toLong()
      val constraints = Constraints.Builder()
         .setRequiredNetworkType(
            if (PreferenceData.getInstance().isWifiOnly()) NetworkType.UNMETERED else NetworkType.CONNECTED
         )
         .build()

      // WorkManager enforces a 15-minute minimum periodic interval --
      // comfortably under every value PreferenceData's interval setting
      // actually offers (hours), so no clamping needed here.
      val request = PeriodicWorkRequestBuilder<SyncWorker>(intervalHours, TimeUnit.HOURS)
         .setConstraints(constraints)
         .build()

      workManager.enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
   }

   /**
    * Triggers an immediate one-off sync (pull-to-refresh). Deduplicated
    * by unique work name with the KEEP policy, so a rapid double
    * pull-to-refresh doesn't queue a second sync on top of one already
    * running.
    */
   fun syncNow(context: Context) {
      val request = OneTimeWorkRequestBuilder<SyncWorker>().build()
      WorkManager.getInstance(context.applicationContext)
         .enqueueUniqueWork(MANUAL_WORK_NAME, ExistingWorkPolicy.KEEP, request)
   }
}
