package com.odiousapps.nextcloudcookbook.services.sync

import android.app.AlarmManager
import android.app.IntentService
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.odiousapps.nextcloudcookbook.nextcloudapi.Accounts
import com.odiousapps.nextcloudcookbook.nextcloudapi.Sync
import com.odiousapps.nextcloudcookbook.notifications.NotificationChannelManager
import com.odiousapps.nextcloudcookbook.notifications.NotificationChannelManager.Companion.SYNC_SERVICE_NOTIFICATION_ID
import com.odiousapps.nextcloudcookbook.reciever.LocalBroadcastReceiver
import com.odiousapps.nextcloudcookbook.settings.PreferenceData
import kotlinx.coroutines.runBlocking
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * Service for syncing recipes (onRefresh or scheduled).
 *
 * @author Felix N&uuml;sse
 * @version 1.0, 13.03.23
 */
class SyncService : IntentService("SyncService") {

   companion object {
      val TAG = SyncService::class.java.toString()
      private const val SECOND = 1000
      private const val MINUTE = SECOND * 60
      private const val HOUR = MINUTE * 60
      private const val PENDINGINTENT_ID = 33559911

      const val SYNC_SERVICE_START_BROADCAST = "SYNC_SERVICE_START_BROADCAST"
      const val SYNC_SERVICE_UPDATE_BROADCAST = "SYNC_SERVICE_UPDATE_BROADCAST"
      const val SYNC_SERVICE_UPDATE_STATUS = "SYNC_SERVICE_UPDATE_STATUS"
      const val SYNC_SERVICE_UPDATE_STATUS_START = "SYNC_SERVICE_UPDATE_STATUS_START"
      const val SYNC_SERVICE_UPDATE_STATUS_END = "SYNC_SERVICE_UPDATE_STATUS_END"
      const val SYNC_SERVICE_INTERVAL_DEFAULT = 24
      const val SYNC_SERVICE_WIFI_ONLY_DEFAULT = true
   }

   fun startServiceScheduling(context: Context) {

      if (Accounts(context).getCurrentAccount() == null) {
         //no sso, dont schedule
         return
      }

      if (!PreferenceData.getInstance().isSyncServiceEnabled()) {
         // Sync disabled. Dont schedule.
         return
      }

      val broadcastIntent = Intent(context, LocalBroadcastReceiver::class.java)
      broadcastIntent.action = SYNC_SERVICE_START_BROADCAST
      val pendingIntent =
         PendingIntent.getBroadcast(context, PENDINGINTENT_ID, broadcastIntent, PendingIntent.FLAG_IMMUTABLE)

      val interval = PreferenceData.getInstance().getSyncServiceInterval()
      val rightNow = Calendar.getInstance()
      rightNow.set(Calendar.MINUTE, 0)
      rightNow.add(Calendar.HOUR, 1)

      val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
      alarms.setRepeating(
         AlarmManager.RTC_WAKEUP,
         rightNow.timeInMillis,
         (HOUR * interval).toLong(),
         pendingIntent
      )
   }

   @Deprecated("Deprecated in Java", ReplaceWith("null"))
   override fun onHandleIntent(intent: Intent?) {
      when (intent?.action) {
         SYNC_SERVICE_START_BROADCAST -> {
            startService(Intent(this, SyncService::class.java))
         }
      }
   }

   @Deprecated("Deprecated in Java", ReplaceWith("null"))
   override fun onBind(intent: Intent): IBinder? {
      return null
   }

   @Deprecated("Deprecated in Java")
   override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
      when (intent?.action) {
         SYNC_SERVICE_START_BROADCAST -> {
            startService(Intent(this, SyncService::class.java))
         }
      }

      NotificationChannelManager.createSyncServiceNotificationChannel(this)
      val serviceNotification = NotificationChannelManager.createSyncServiceNotification(this)
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
         startForeground(SYNC_SERVICE_NOTIFICATION_ID, serviceNotification.build())
      } else {
         startForeground(
            SYNC_SERVICE_NOTIFICATION_ID, serviceNotification.build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
         )
      }
      sync()
      return START_STICKY
   }

   private fun sync() {
      sendSyncStartEvent()
      Executors.newSingleThreadExecutor().submit {
         try {
            val sync = Sync(this)
            sync.synchronizeRecipes()
            sync.closeAPI()
         } catch (e: Exception) {
            Log.e(TAG, "Error Syncing: " + e.message)
         } finally {
            sendSyncEndEvent()
            stopForeground(STOP_FOREGROUND_REMOVE)
         }
      }
   }

   private fun sendSyncStartEvent() {
      val intent = Intent(SYNC_SERVICE_UPDATE_BROADCAST)
      intent.putExtra(SYNC_SERVICE_UPDATE_STATUS, SYNC_SERVICE_UPDATE_STATUS_START)
      LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

   }

   private fun sendSyncEndEvent() {
      val intent = Intent(SYNC_SERVICE_UPDATE_BROADCAST)
      intent.putExtra(SYNC_SERVICE_UPDATE_STATUS, SYNC_SERVICE_UPDATE_STATUS_END)
      LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
      runBlocking {
         PreferenceData.getInstance().setStorageAccessed(true)
      }
   }
}