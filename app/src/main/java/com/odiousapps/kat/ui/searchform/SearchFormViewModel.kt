/*
 * SearchFormViewModel.kt
 *
 * Copyright 2021 by MicMun
 */
package com.odiousapps.kat.ui.searchform

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.odiousapps.kat.R
import com.odiousapps.kat.db.DbRecipeRepository
import com.odiousapps.kat.db.model.DbKeyword
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

    val keywords: LiveData<List<DbKeyword>>
        field = MutableLiveData<List<DbKeyword>>()

    val searchType: LiveData<Int>
        field = MutableLiveData(R.id.typeKeyword)
    val caseSensitive: LiveData<Boolean>
        field = MutableLiveData(true)
    val exactSearch: LiveData<Boolean>
        field = MutableLiveData(false)

    val currentQuery: LiveData<String>
        field = MutableLiveData("")

    val currentKeyword: LiveData<Int>
        field = MutableLiveData(0)

    fun loadKeywords() {
      uiScope.launch {
         val dbKeywords = repository.getKeywords()
         dbKeywords.collect {
            keywords.postValue(it)
         }
      }
   }
}
