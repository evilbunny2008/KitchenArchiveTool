package com.odiousapps.kat.reciever

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.odiousapps.kat.services.sync.SyncWorker.Companion.SYNC_UPDATE_BROADCAST
import com.odiousapps.kat.services.sync.SyncWorker.Companion.SYNC_UPDATE_STATUS
import com.odiousapps.kat.services.sync.SyncWorker.Companion.SYNC_UPDATE_STATUS_START
import com.odiousapps.kat.ui.recipelist.RecipeListFragment

/**
 * Relays SyncWorker's start/end status to whichever RecipeListFragment
 * is currently registered, so it can show/hide the pull-to-refresh
 * spinner. Registered only via LocalBroadcastManager (in-process) from
 * RecipeListFragment.setupBroadcastListener() -- unlike before, this
 * class doesn't also need to be reachable as a manifest-registered
 * system receiver, since sync is no longer triggered by a raw
 * AlarmManager broadcast (see SyncScheduler's doc comment).
 */
class LocalBroadcastReceiver(private val recipeFragment: RecipeListFragment) : BroadcastReceiver() {

   companion object {
      val TAG = LocalBroadcastReceiver::class.java.toString()
   }

   override fun onReceive(context: Context?, intent: Intent?) {
      Log.d(TAG, "Intent Recieved")

      if (intent?.action == SYNC_UPDATE_BROADCAST) {
         val status = intent.getStringExtra(SYNC_UPDATE_STATUS)
         recipeFragment.notifyUpdate(status == SYNC_UPDATE_STATUS_START)
      }
   }
}
