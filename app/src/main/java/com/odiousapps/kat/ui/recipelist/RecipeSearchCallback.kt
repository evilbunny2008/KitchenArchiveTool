package com.odiousapps.kat.ui.recipelist

import com.odiousapps.kat.data.CategoryFilter
import com.odiousapps.kat.data.RecipeFilter

interface RecipeSearchCallback {
    fun searchRecipes(filter: RecipeFilter)
    fun searchCategory(filter: CategoryFilter)
    fun showSortSelector()
}
