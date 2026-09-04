/*
 * MainApplication.kt
 *
 * Copyright 2020 by MicMun
 */
package com.odiousapps.kat

import android.app.Application
import android.os.StrictMode
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.odiousapps.kat.services.RemainReceiver

/**
 * Application of the app.
 *
 * @author MicMun
 * @version 1.2, 27.11.21
 */
class MainApplication : Application(), ViewModelStoreOwner {
   val dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

   private val appViewModelStore: ViewModelStore by lazy {
      ViewModelStore()
   }

   override val viewModelStore: ViewModelStore
      get() = appViewModelStore

   override fun onCreate() {
      super.onCreate()
      Log.i(TAG, "Starting app")
      AppContext = this

      if (BuildConfig.DEBUG) {
         // "A resource failed to call close." on its own, from the
         // finalizer, doesn't say *where* the leaked object was opened --
         // this does: it logs a full stack trace pointing at the
         // allocation site the next time a Closeable (Cursor, Stream,
         // NextcloudAPI connection, etc.) gets garbage-collected without
         // close() having been called on it. penaltyLog() only (not
         // penaltyDeath()) so it's purely diagnostic and never crashes
         // the app -- debug builds only, no effect on release.
         StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
               .detectLeakedClosableObjects()
               .penaltyLog()
               .build()
         )
      }
   }

   companion object {
      private const val TAG = "MainApplication"
      lateinit var AppContext: MainApplication
   }

   var receiver: RemainReceiver? = null

}
