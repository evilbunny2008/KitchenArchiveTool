/*
 * CurrentSettingViewModel
 *
 * Copyright 2021 by MicMun
 */
package de.micmun.android.nextcloudcookbook.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import de.micmun.android.nextcloudcookbook.data.CategoryFilter
import de.micmun.android.nextcloudcookbook.data.SortValue
import de.micmun.android.nextcloudcookbook.settings.PreferenceData
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
   private val _category = MutableLiveData<CategoryFilter>()
   val category: LiveData<CategoryFilter>
      get() = _category

   // category changed
   private val _categoryChanged = MutableLiveData<Boolean>()
   val categoryChanged: LiveData<Boolean>
      get() = _categoryChanged

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
      val changed = _category.value == cat
      _category.value = cat
      _categoryChanged.value = changed
   }

   fun resetCategoryChanged() {
      _categoryChanged.value = false
   }
}
