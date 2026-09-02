package de.micmun.android.nextcloudcookbook.reciever

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import de.micmun.android.nextcloudcookbook.services.sync.SyncService
import de.micmun.android.nextcloudcookbook.services.sync.SyncService.Companion.SYNC_SERVICE_START_BROADCAST
import de.micmun.android.nextcloudcookbook.services.sync.SyncService.Companion.SYNC_SERVICE_UPDATE_BROADCAST
import de.micmun.android.nextcloudcookbook.services.sync.SyncService.Companion.SYNC_SERVICE_UPDATE_STATUS
import de.micmun.android.nextcloudcookbook.services.sync.SyncService.Companion.SYNC_SERVICE_UPDATE_STATUS_START
import de.micmun.android.nextcloudcookbook.settings.PreferenceData
import de.micmun.android.nextcloudcookbook.ui.recipelist.RecipeListFragment
import de.micmun.android.nextcloudcookbook.util.ConnectivityCheck


class LocalBroadcastReceiver() : BroadcastReceiver() {

   companion object {
      val TAG = LocalBroadcastReceiver::class.java.toString()
   }

   var mRecipeFragment: RecipeListFragment? = null

   constructor(recipeFragment: RecipeListFragment) : this() {
      mRecipeFragment = recipeFragment
   }

   override fun onReceive(context: Context?, intent: Intent?) {
      Log.d(TAG, "Intent Recieved")

      val action = intent!!.action
      if (action != null) {
         when (action) {
            SYNC_SERVICE_UPDATE_BROADCAST -> {
               val status = intent.getStringExtra(SYNC_SERVICE_UPDATE_STATUS)
               if (status == SYNC_SERVICE_UPDATE_STATUS_START) {
                  mRecipeFragment?.notifyUpdate(true)
               } else {
                  mRecipeFragment?.notifyUpdate(false)
               }
            }
            SYNC_SERVICE_START_BROADCAST -> {
               mRecipeFragment?.onRefresh()
            }
         }
      }
   }
}