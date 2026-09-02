package com.odiousapps.nextcloudcookbook.reciever

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.odiousapps.nextcloudcookbook.services.sync.SyncService

class BootReceiver : BroadcastReceiver() {

    private val tag = BootReceiver::class.toString()

    override fun onReceive(context: Context, intent: Intent) {
        if(intent.action== Intent.ACTION_BOOT_COMPLETED){
            Log.d(tag, "BootReceiver: Started SyncScheduling!")
            SyncService().startServiceScheduling(context)
        }
    }
}