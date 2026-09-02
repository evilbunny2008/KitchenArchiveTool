/*
 * SearchFormViewModel.kt
 *
 * Copyright 2021 by MicMun
 */
package com.odiousapps.nextcloudcookbook.ui.searchform

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.odiousapps.nextcloudcookbook.R
import com.odiousapps.nextcloudcookbook.db.DbRecipeRepository
import com.odiousapps.nextcloudcookbook.db.model.DbKeyword
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * ViewModel for search formular.
 *
 * @author MicMun
 * @version 1.0, 24.04.21
 */
class SearchFormViewModel(application: Application) : AndroidViewModel(application) {
   private val repository = DbRecipeRepository.getInstance(application)

   // coroutines
   private var viewModelJob = Job()
   private val uiScope = CoroutineScope(Dispatchers.Main + viewModelJob)

   private val _keywords = MutableLiveData<List<DbKeyword>>()
   val keywords: LiveData<List<DbKeyword>>
      get() = _keywords

   private val _searchType = MutableLiveData(R.id.typeKeyword)
   val searchType: LiveData<Int>
      get() = _searchType
   private val _caseSensitive = MutableLiveData(true)
   val caseSensitive: LiveData<Boolean>
      get() = _caseSensitive
   private val _exactSearch = MutableLiveData(false)
   val exactSearch: LiveData<Boolean>
      get() = _exactSearch

   private val _currentQuery = MutableLiveData("")
   val currentQuery: LiveData<String>
      get() = _currentQuery

   private val _currentKeyword = MutableLiveData(0)
   val currentKeyword: LiveData<Int>
      get() = _currentKeyword

   fun loadKeywords() {
      uiScope.launch {
         val dbKeywords = repository.getKeywords()
         dbKeywords.collect() {
            _keywords.postValue(it)
         }
      }
   }
}
