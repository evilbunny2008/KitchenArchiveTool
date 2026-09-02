/*
 * MainApplication.kt
 *
 * Copyright 2020 by MicMun
 */
package de.micmun.android.nextcloudcookbook

import android.app.Application
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import de.micmun.android.nextcloudcookbook.services.RemainReceiver

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
   }

   companion object {
      private const val TAG = "MainApplication"
      lateinit var AppContext: MainApplication
   }

   var receiver: RemainReceiver? = null

}
