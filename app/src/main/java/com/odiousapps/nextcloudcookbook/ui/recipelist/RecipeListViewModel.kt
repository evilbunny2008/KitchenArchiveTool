/*
 * RecipeViewModel.kt
 *
 * Copyright 2020 by MicMun
 */
package com.odiousapps.nextcloudcookbook.ui.recipelist

import android.annotation.SuppressLint
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asLiveData
import com.odiousapps.nextcloudcookbook.data.CategoryFilter
import com.odiousapps.nextcloudcookbook.data.RecipeFilter
import com.odiousapps.nextcloudcookbook.data.SortValue
import com.odiousapps.nextcloudcookbook.db.DbRecipeRepository
import com.odiousapps.nextcloudcookbook.db.model.DbRecipePreview
import com.odiousapps.nextcloudcookbook.json.JsonRecipeRepository
import com.odiousapps.nextcloudcookbook.json.model.Recipe
import com.odiousapps.nextcloudcookbook.util.Recipe2DbRecipeConverter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration
import java.util.stream.Collectors

/**
 * ViewModel for list of recipes.
 *
 * @author MicMun
 * @version 2.0, 29.05.22
 */
class RecipeListViewModel(private val app: Application) : AndroidViewModel(app) {
   // coroutines
   private var viewModelJob = Job()

   private val uiScope = CoroutineScope(Dispatchers.Main + viewModelJob)

   private val recipeRepository = DbRecipeRepository.getInstance(app)
   val categories = recipeRepository.getCategories().asLiveData(Duration.ofSeconds(10), Dispatchers.Main)

    val recipes: LiveData<List<DbRecipePreview>>
        field = MutableLiveData<List<DbRecipePreview>>()

    // on updating
   val isUpdating = MutableLiveData(false)
   val isLoaded = MutableLiveData(false)

   private var recipeDir: String = ""

   // sorting and category
   private var sort: SortValue = SortValue.NAME_A_Z
   private var filter: RecipeFilter? = null
   private var catFilter: CategoryFilter = CategoryFilter(CategoryFilter.CategoryFilterOption.ALL_CATEGORIES)

   // navigate to recipe
   private val _navigateToRecipe = MutableLiveData<Long?>()
   val navigateToRecipe
      get() = _navigateToRecipe

   fun onRecipeClicked(id: Long) {
      _navigateToRecipe.value = id
   }

   fun onRecipeNavigated() {
      _navigateToRecipe.value = null
   }

   fun loadRecipes() {
      var tmp: Flow<List<DbRecipePreview>>

      uiScope.launch {
         tmp =
            if (filter != null) {
               Log.d("RecipeListViewModel", "SEARCH ! $filter")
               recipeRepository.filterAll(sort, filter!!, recipeDir)
            } else {
                when (catFilter.type) {
                    CategoryFilter.CategoryFilterOption.ALL_CATEGORIES if sort == SortValue.NAME_A_Z -> {
                        recipeRepository.getAllRecipePreviews(recipeDir)
                    }
                    CategoryFilter.CategoryFilterOption.ALL_CATEGORIES -> {
                        recipeRepository.sort(sort, recipeDir)
                    }
                    CategoryFilter.CategoryFilterOption.UNCATEGORIZED -> {
                        recipeRepository.filterUncategorized(sort, recipeDir, filter)
                    }
                    else -> {
                        recipeRepository.filterCategory(sort, catFilter.name, recipeDir)
                    }
                }
            }
         tmp.collect {
            recipes.value = it
         }
      }
   }

   // read recipes
   fun initRecipes(path: String = "", hidden: Boolean = false) {
      if (path.isNotEmpty()) {
         recipeDir = path
      }
      val dir = path.ifEmpty { recipeDir }

      if (dir.isEmpty()) {
         if (!hidden) isUpdating.postValue(false)
         return
      }

      if (!hidden) isUpdating.postValue(true)

      uiScope.launch {
         val list = getRecipesFromRepo(dir)
         val dbList = list.stream()
            .map { Recipe2DbRecipeConverter(it).convert() }
            .collect(Collectors.toList())
         recipeRepository.insertAll(dbList, dir)

         isLoaded.postValue(true)
         if (!hidden) isUpdating.postValue(false)
      }
   }

   private suspend fun getRecipesFromRepo(path: String): List<Recipe> {

      return withContext(Dispatchers.IO) {

         val repositoryRecipes = JsonRecipeRepository.getInstance()
            .getAllRecipes(app, path, recipeRepository.getAllFileInfos())

         for (recipeInfo in recipeRepository.getAllFileInfos()) {
            if (!File(recipeInfo.filePath).exists()) {
               var filename = recipeInfo.filePath.substring(0, recipeInfo.filePath.lastIndexOf("/"))
               filename = filename.substring(filename.lastIndexOf("/") + 1, filename.length)
               recipeRepository.deleteRecipe(filename)
            }
         }
         repositoryRecipes
      }
   }

   // category filter
   fun filterRecipesByCategory(catFilter: CategoryFilter?) {
      if (catFilter == null) {
         this.catFilter = CategoryFilter(CategoryFilter.CategoryFilterOption.ALL_CATEGORIES)
      } else {
         this.catFilter = catFilter
      }
   }

   fun sortList(sort: SortValue) {
      this.sort = sort
   }

   fun search(filter: RecipeFilter?) {
      this.filter = filter
   }

   @SuppressLint("EmptySuperCall")
   override fun onCleared() {
      super.onCleared()
      viewModelJob.cancel()
   }

   fun getRecipeDir(): String {
      return recipeDir
   }
}

class RecipeListViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
   @Suppress("UNCHECKED_CAST")
   override fun <T : ViewModel> create(modelClass: Class<T>): T {
      if (modelClass.isAssignableFrom(RecipeListViewModel::class.java)) {
         return RecipeListViewModel(application) as T
      }
      throw IllegalArgumentException("Unknown ViewModel class")
   }
}
