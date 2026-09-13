/*
 * SyncNotificationHelper.kt
 *
 * Copyright 2026 by MicMun
 */
package com.odiousapps.kat.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.odiousapps.kat.R

/**
 * Shows sync progress in a notification for periodic (background) syncs
 * -- the person isn't necessarily watching any screen while one of those
 * runs, so a dialog wouldn't be seen; a notification is, matching how
 * the official Nextcloud app surfaces its own background sync progress.
 *
 * This posts a plain, dismissible notification directly via
 * NotificationManagerCompat -- it never calls setForeground()/
 * startForeground(). See SyncScheduler's own doc comment for why that
 * distinction matters here specifically: setForeground() is what
 * actually requires the FOREGROUND_SERVICE permission this app
 * deliberately avoids, since it promotes the whole Worker to a
 * foreground-service-backed task (with an un-dismissable notification)
 * specifically to protect it from being killed under Doze/background
 * limits -- protection a quick check-and-download doesn't need. A plain
 * notification just displays progress; it doesn't change how the work
 * itself is scheduled or protected.
 */
object SyncNotificationHelper {

   private const val SYNC_CHANNEL = "nc_recipe_sync"

   // Distinct from NotificationChannelManager.TIMER_NOTIFICATION_ID so
   // showing sync progress can never clobber an active cook timer
   // notification, or vice versa.
   private const val SYNC_NOTIFICATION_ID = 1478544

   /**
    * Creates the notification channel when Android version >= O (API 26+).
    * IMPORTANCE_LOW: this updates frequently (once per recipe) while a
    * sync runs, and none of those updates need to interrupt with sound
    * or a heads-up popup -- it's simply visible progress, matching the
    * quiet, informational nature of a plain (non-foreground-service)
    * notification described in this object's own doc comment above.
    */
   fun createSyncNotificationChannel(context: Context) {
      val name = context.getString(R.string.sync_channel_name)
      val descriptionText = context.getString(R.string.sync_channel_description)
      val channel = NotificationChannel(SYNC_CHANNEL, name, NotificationManager.IMPORTANCE_LOW).apply {
         description = descriptionText
      }
      NotificationManagerCompat.from(context).createNotificationChannel(channel)
   }

   /**
    * Shows/updates the progress notification. Silently does nothing if
    * the POST_NOTIFICATIONS permission (API 33+) was never granted or
    * has been revoked -- the sync itself doesn't depend on this
    * notification at all, so there's nothing to fail; the person just
    * won't see progress for this particular background run. This
    * deliberately doesn't request the permission itself -- it's already
    * requested elsewhere (see RecipeDetailFragment, for the cook timer
    * feature) the first time a person actually needs a notification for
    * something they explicitly did, which is a better moment to ask than
    * a background sync they may not even be aware is happening.
    */
   fun showProgress(context: Context, item: Int, overall: Int, title: String) {
      if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
         != PackageManager.PERMISSION_GRANTED
      ) {
         return
      }

      val notification = NotificationCompat.Builder(context, SYNC_CHANNEL)
         .setContentTitle(context.getString(R.string.sync_notification_title))
         .setContentText(context.getString(R.string.sync_notification_progress, item, overall, title))
         .setSmallIcon(R.drawable.ic_sync)
         .setProgress(overall, item, false)
         .setOnlyAlertOnce(true)
         .setOngoing(true)
         .setPriority(NotificationCompat.PRIORITY_LOW)
         .build()

      NotificationManagerCompat.from(context).notify(SYNC_NOTIFICATION_ID, notification)
   }

   /** Dismisses the progress notification -- called once a background sync finishes, success or failure either way. */
   fun clear(context: Context) {
      NotificationManagerCompat.from(context).cancel(SYNC_NOTIFICATION_ID)
   }
}
