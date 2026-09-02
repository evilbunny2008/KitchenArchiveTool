package com.odiousapps.nextcloudcookbook.reciever

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.odiousapps.nextcloudcookbook.services.sync.SyncService.Companion.SYNC_SERVICE_START_BROADCAST
import com.odiousapps.nextcloudcookbook.services.sync.SyncService.Companion.SYNC_SERVICE_UPDATE_BROADCAST
import com.odiousapps.nextcloudcookbook.services.sync.SyncService.Companion.SYNC_SERVICE_UPDATE_STATUS
import com.odiousapps.nextcloudcookbook.services.sync.SyncService.Companion.SYNC_SERVICE_UPDATE_STATUS_START
import com.odiousapps.nextcloudcookbook.ui.recipelist.RecipeListFragment


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