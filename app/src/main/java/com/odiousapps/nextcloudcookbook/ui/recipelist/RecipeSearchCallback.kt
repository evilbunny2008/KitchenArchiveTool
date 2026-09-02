package com.odiousapps.nextcloudcookbook.ui.recipelist

import com.odiousapps.nextcloudcookbook.data.CategoryFilter
import com.odiousapps.nextcloudcookbook.data.RecipeFilter

interface RecipeSearchCallback {
    fun searchRecipes(filter: RecipeFilter)
    fun searchCategory(filter: CategoryFilter)
    fun showSortSelector()
}