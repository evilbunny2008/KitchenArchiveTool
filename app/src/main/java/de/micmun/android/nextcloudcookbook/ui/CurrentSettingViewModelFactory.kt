/*
 * CurrentSettingViewModelFactory.kt
 *
 * Copyright 2021 by MicMun
 */
package de.micmun.android.nextcloudcookbook.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * Factory for CurrentSettingViewModel.
 *
 * @author MicMun
 * @version 1.0, 26.01.21
 */
class CurrentSettingViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
   override fun <T : ViewModel> create(modelClass: Class<T>): T {
      if (modelClass.isAssignableFrom(CurrentSettingViewModel::class.java)) {
         @Suppress("UNCHECKED_CAST")
         return CurrentSettingViewModel(application) as T
      }
      throw IllegalArgumentException("Unknown ViewModel class")
   }
}
