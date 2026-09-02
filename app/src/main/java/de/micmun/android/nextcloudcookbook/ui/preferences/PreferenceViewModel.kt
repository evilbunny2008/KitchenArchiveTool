/*
 * PreferenceViewModel.kt
 *
 * Copyright 2020 by MicMun
 */
package de.micmun.android.nextcloudcookbook.ui.preferences

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.micmun.android.nextcloudcookbook.settings.PreferenceData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * ViewModel for preferences.
 *
 * @author MicMun
 * @version 1.4, 27.11.21
 */
class PreferenceViewModel(application: Application) : AndroidViewModel(application) {
   private val prefData = PreferenceData.getInstance()

   internal val recipeDirectory: Flow<String> = prefData.getRecipeDir()
   internal val theme: Flow<Int> = prefData.getTheme()

   fun setRecipeDirectory(recipeDirectory: String) {
      viewModelScope.launch(Dispatchers.IO) {
         prefData.setRecipeDir(recipeDirectory)
      }
   }

   fun setTheme(theme: Int) {
      viewModelScope.launch(Dispatchers.IO) {
         prefData.setTheme(theme)
      }
   }

   fun setSyncServiceInterval(interval: Int) {
      viewModelScope.launch(Dispatchers.IO) {
         prefData.setSyncServiceInterval(interval)
      }
   }

   fun setWifiOnly(enabled: Boolean) {
      viewModelScope.launch(Dispatchers.IO) {
         prefData.setWifiOnly(enabled)
      }
   }

   fun setInitialized(init: Boolean) {
      viewModelScope.launch(Dispatchers.IO) {
         prefData.setInitialised(init)
      }
   }

   fun setStorageAccessed(access: Boolean) {
      viewModelScope.launch(Dispatchers.IO) {
         prefData.setStorageAccessed(access)
      }
   }
}
