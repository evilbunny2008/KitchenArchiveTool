package com.odiousapps.nextcloudcookbook.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.odiousapps.nextcloudcookbook.R

/**
 * Manager for notification channels.
 *
 * @author MicMun
 * @version 1.1, 05.03.23
 */
class NotificationChannelManager {

   companion object {
      private const val SYNC_SERVICE_CHANNEL = "SYNC_SERVICE_CHANNEL"
      const val SYNC_SERVICE_NOTIFICATION_ID = 1478543

      // Channel ID for notification channel
      private const val TIMER_CHANNEL = "nc_cooktimer"
      const val TIMER_NOTIFICATION_ID = 1478543

      fun createSyncServiceNotificationChannel(context: Context) {
         val name = context.getString(R.string.sync_service_channel_name)
         val descriptionText = context.getString(R.string.sync_service_channel_description)
         val importance = NotificationManager.IMPORTANCE_DEFAULT
         val channel = NotificationChannel(SYNC_SERVICE_CHANNEL, name, importance).apply {
            description = descriptionText
         }
         // Register the channel with the system
         val notificationManager: NotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
         notificationManager.createNotificationChannel(channel)
      }

      fun createSyncServiceNotification(context: Context): NotificationCompat.Builder {
         return NotificationCompat.Builder(context, SYNC_SERVICE_CHANNEL)
            .setSmallIcon(R.drawable.appicon_unscaled)
            .setContentTitle(context.getString(R.string.sync_service_notification_title))
            .setContentText(context.getString(R.string.sync_service_notification_content))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setStyle(
               NotificationCompat.BigTextStyle().bigText(context.getString(R.string.sync_service_notification_content))
            )
            .setAutoCancel(true)
      }

      /**
       * Creates a notification channel when Android version >= O (API 26+).
       */
      fun createCookTimerNotificationChannel(context: Context) {
         // Create the NotificationChannel, but only on API 26+ because
         // the NotificationChannel class is new and not in the support library
         val name = context.getString(R.string.channel_name)
         val descriptionText = context.getString(R.string.channel_description)
         val importance = NotificationManager.IMPORTANCE_DEFAULT
         val channel = NotificationChannel(TIMER_CHANNEL, name, importance).apply {
            description = descriptionText
         }
         // Register the channel with the system
         NotificationManagerCompat.from(context).createNotificationChannel(channel)
      }

      fun createCookTimerNotification(context: Context, pendingIntent: PendingIntent): NotificationCompat.Builder {
         // create notification
         return NotificationCompat.Builder(context, TIMER_CHANNEL)
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentTitle(context.getString(R.string.notification_title))
            .setSmallIcon(R.drawable.ic_timer)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setOngoing(true)
      }

   }
}