/*
 * RecipeViewModel.kt
 *
 * Copyright 2020 by MicMun
 */
package com.odiousapps.kat.ui.recipelist

import android.annotation.SuppressLint
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.odiousapps.kat.data.CategoryFilter
import com.odiousapps.kat.data.RecipeFilter
import com.odiousapps.kat.data.SortValue
import com.odiousapps.kat.db.DbRecipeRepository
import com.odiousapps.kat.db.model.DbRecipePreview
import com.odiousapps.kat.json.JsonRecipeRepository
import com.odiousapps.kat.json.model.Recipe
import com.odiousapps.kat.util.Recipe2DbRecipeConverter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
   // Backed by a cancellable Job (loadCategoriesJob, see initRecipes) rather
   // than a single Flow bound once at construction: recipeDir can change
   // over this ViewModel's lifetime (switching accounts), and the same
   // stale-collector risk loadRecipes() already had to be fixed for
   // applies here too -- an old collector left running after a switch
   // would keep emitting a different account's categories into this list.
   val categories: LiveData<List<String>>
      field = MutableLiveData<List<String>>(emptyList())
   private var loadCategoriesJob: Job? = null

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

   // The Job for whichever loadRecipes() call is currently collecting a
   // Flow into `recipes`. Room's Flow queries invalidate at the table
   // level, not per-query -- without cancelling the previous collection
   // before starting a new one, every past call (a previous account, a
   // previous sort/filter) stays alive and keeps re-emitting its own
   // results into `recipes` whenever *anything* writes to the recipes
   // table, including a background sync for an account since switched
   // away from. Whichever collector happens to fire last wins, regardless
   // of which account/filter is actually the current one -- this is what
   // caused recipes from a previously-active account to reappear after
   // switching to a different (empty) account.
   private var loadRecipesJob: Job? = null

   fun loadRecipes() {
      loadRecipesJob?.cancel()
      var tmp: Flow<List<DbRecipePreview>>

      loadRecipesJob = uiScope.launch {
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
         tmp.map { list ->
            // Defensive dedup: a recipe can end up as two separate Room rows
            // if a stale row from before an insert/update matching change
            // doesn't get recognized as "the same recipe" as a freshly
            // re-synced one. Keeps the first occurrence per name, following

            // whichever sort order is currently active.
            list.distinctBy { it.name }
         }.collect {
            recipes.value = it
         }
      }
   }

   /**
    * Removes duplicate recipe rows for the current account. Intended to be
    * called from an explicit, user-initiated refresh -- see
    * DbRecipeRepository.deleteDuplicates()'s doc comment.
    */
   fun removeDuplicateRecipes() {
      if (recipeDir.isNotEmpty()) {
         recipeRepository.deleteDuplicates(recipeDir)
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

      loadCategories(dir)

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

   /**
    * Reloads the category-filter list for [dir]. Always cancels and
    * relaunches rather than reusing an in-flight collector -- see this
    * property's own doc comment above for why (recipeDir can change
    * across this ViewModel's lifetime, and an old collector left running
    * for a previous account would otherwise keep leaking that account's
    * categories back into the list after switching away from it).
    */
   private fun loadCategories(dir: String) {
      loadCategoriesJob?.cancel()
      loadCategoriesJob = uiScope.launch {
         recipeRepository.getCategories(dir).collect { categories.value = it }
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
