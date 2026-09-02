/*
 * CurrentSettingViewModel
 *
 * Copyright 2021 by MicMun
 */
package com.odiousapps.nextcloudcookbook.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.odiousapps.nextcloudcookbook.data.CategoryFilter
import com.odiousapps.nextcloudcookbook.data.SortValue
import com.odiousapps.nextcloudcookbook.settings.PreferenceData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * LiveData of the settings.
 *
 * @author MicMun
 * @version 1.5, 27.11.21
 */
class CurrentSettingViewModel(application: Application) : AndroidViewModel(application) {
   private val prefData = PreferenceData.getInstance()
   val recipeDirectory: Flow<String> = prefData.getRecipeDir()
   val sorting: Flow<Int> = prefData.getSort()
   val storageAccessed: Flow<Boolean> = prefData.isStorageAccessed()

    // category
    val category: LiveData<CategoryFilter>
        field = MutableLiveData<CategoryFilter>()

    // category changed
    val categoryChanged: LiveData<Boolean>
        field = MutableLiveData<Boolean>()

    fun setSorting(sort: Int, mainActivity: MainActivity) {
      mainActivity.setSortIcon(SortValue.getByValue(sort))
      viewModelScope.launch(Dispatchers.IO) {
         prefData.setSort(sort)
      }
   }

   fun setStorageAccess(access: Boolean) {
      viewModelScope.launch(Dispatchers.IO) {
         prefData.setStorageAccessed(access)
      }
   }

   fun setNewCategory(cat: CategoryFilter) {
      val changed = category.value == cat
      category.value = cat
      categoryChanged.value = changed
   }

   fun resetCategoryChanged() {
      categoryChanged.value = false
   }
}
