/*
 * PreferenceData.kt
 *
 * Copyright 2021 by MicMun
 */
package com.odiousapps.nextcloudcookbook.settings

import android.content.SharedPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.odiousapps.nextcloudcookbook.MainApplication
import com.odiousapps.nextcloudcookbook.services.sync.SyncService
import com.odiousapps.nextcloudcookbook.services.sync.SyncService.Companion.SYNC_SERVICE_INTERVAL_DEFAULT
import com.odiousapps.nextcloudcookbook.services.sync.SyncService.Companion.SYNC_SERVICE_WIFI_ONLY_DEFAULT
import com.odiousapps.nextcloudcookbook.ui.MainActivity.Companion.THEME_PREFERENCE_DEFAULT
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/**
 * Datastore for settings.
 *
 * @author MicMun
 * @version 1.0, 27.11.21
 */
class PreferenceData private constructor() {
   private val isInitializedKey = booleanPreferencesKey(Pref.IS_INIT)
   private val recipeDirKey = stringPreferencesKey(Pref.RECIPE_DIR)
   private val themeKey = intPreferencesKey(Pref.THEME)
   private val screenKeepalive = booleanPreferencesKey(Pref.SCREEN_KEEPALIVE)
   private val sortKey = intPreferencesKey(Pref.SORT)
   private val isStorageAccessedKey = booleanPreferencesKey(Pref.STORAGE_ACCESS)
   private val isSyncServiceEnabled = intPreferencesKey(Pref.SYNC_SERVICE)
   private val isSyncWifiOnly = booleanPreferencesKey(Pref.SYNC_WIFI_ONLY)

   companion object {
      @Volatile
      private var INSTANCE: PreferenceData? = null

      fun getInstance(): PreferenceData {
         synchronized(PreferenceData::class) {
            var instance = INSTANCE

            if (instance == null) {
               instance = PreferenceData()
               INSTANCE = instance
            }

            return instance
         }
      }
   }

   fun isInitializedSync(): Boolean {
      var isInit = false

      runBlocking {
         isInit = MainApplication.AppContext.dataStore.data
            .map { preferences ->
               preferences[isInitializedKey] ?: false
            }
            .first()
      }

      return isInit
   }

   fun getRecipeDir(): Flow<String> {
      return MainApplication.AppContext.dataStore.data
         .map { preferences ->
            preferences[recipeDirKey] ?: ""
         }
   }

   fun getTheme(): Flow<Int> {
      return MainApplication.AppContext.dataStore.data
         .map { preferences ->
            preferences[themeKey] ?: THEME_PREFERENCE_DEFAULT
         }
   }

   fun getThemeSync(): Int {
      var theme = THEME_PREFERENCE_DEFAULT

      runBlocking {
         theme = MainApplication.AppContext.dataStore.data
            .map { preferences ->
               preferences[themeKey] ?: THEME_PREFERENCE_DEFAULT
            }
            .first()
      }

      return theme
   }

   fun getSort(): Flow<Int> {
      return MainApplication.AppContext.dataStore.data
         .map { preferences ->
            preferences[sortKey] ?: 0
         }
   }

   fun isStorageAccessed(): Flow<Boolean> {
      return MainApplication.AppContext.dataStore.data
         .map { preferences ->
            preferences[isStorageAccessedKey] ?: false
         }
   }

   fun getSyncServiceInterval(): Int {
      var interval = SYNC_SERVICE_INTERVAL_DEFAULT
      runBlocking {
         interval = MainApplication.AppContext.dataStore.data
            .map { preferences ->
               preferences[isSyncServiceEnabled] ?: SYNC_SERVICE_INTERVAL_DEFAULT
            }
            .first()
      }
      return interval
   }

   fun isSyncServiceEnabled(): Boolean {
      return getSyncServiceInterval() > 0
   }

   fun setSyncServiceEnabled() {
      setSyncServiceInterval(SYNC_SERVICE_INTERVAL_DEFAULT)
   }

   fun setSyncServiceInterval(interval: Int) {
      runBlocking {
         MainApplication.AppContext.dataStore.edit { preferences ->
            preferences[isSyncServiceEnabled] = interval
         }
         SyncService().startServiceScheduling(MainApplication.AppContext)
      }
   }

   fun setWifiOnly(enable: Boolean) {
      runBlocking {
         MainApplication.AppContext.dataStore.edit { preferences ->
            preferences[isSyncWifiOnly] = enable
         }
         SyncService().startServiceScheduling(MainApplication.AppContext)
      }
   }

   fun isWifiOnly(): Boolean {
      var enabled = SYNC_SERVICE_WIFI_ONLY_DEFAULT
      runBlocking {
         enabled = MainApplication.AppContext.dataStore.data
            .map { preferences ->
               preferences[isSyncWifiOnly] ?: SYNC_SERVICE_WIFI_ONLY_DEFAULT
            }
            .first()
      }
      return enabled
   }

   suspend fun setInitialised(isInit: Boolean) {
      MainApplication.AppContext.dataStore.edit { preferences ->
         preferences[isInitializedKey] = isInit
      }
   }

   suspend fun setStorageAccessed(isStorageAccessed: Boolean) {
      MainApplication.AppContext.dataStore.edit { preferences ->
         preferences[isStorageAccessedKey] = isStorageAccessed
      }
   }

   suspend fun setTheme(theme: Int) {
      MainApplication.AppContext.dataStore.edit { preferences ->
         preferences[themeKey] = theme
      }
   }

   suspend fun setSort(sort: Int) {
      MainApplication.AppContext.dataStore.edit { preferences ->
         preferences[sortKey] = sort
      }
   }

   suspend fun setRecipeDir(recipeDir: String) {
      MainApplication.AppContext.dataStore.edit { preferences ->
         preferences[recipeDirKey] = recipeDir
      }
   }

   fun getScreenKeepalive(): Boolean {
      var keepAlive = true
      runBlocking {
         keepAlive = MainApplication.AppContext.dataStore.data
            .map { preferences ->
               preferences[screenKeepalive] ?: keepAlive // use above keepalive value as default
            }
            .first()
      }

      return keepAlive
   }

   /**
    * Migrates the current sharedPreferences to Datastore.
    *
    * @param sharedPreferences SharedPreferences to migrate.
    */
   suspend fun migrateSharedPreferences(sharedPreferences: SharedPreferences) {
      val prefs = sharedPreferences.all
      prefs.keys.forEach { k ->
         when (k) {
            Pref.RECIPE_DIR -> setRecipeDir(prefs[k] as String)
            Pref.STORAGE_ACCESS -> setStorageAccessed(prefs[k] as Boolean)
            Pref.THEME -> setTheme(prefs[k] as Int)
            Pref.SORT -> setSort(prefs[k] as Int)
         }
      }
      if (prefs.isNotEmpty()) { // if true, migration, else first start for the app or migrated yet
         sharedPreferences.edit().clear().apply()
         setInitialised(true)
      }
   }
}
