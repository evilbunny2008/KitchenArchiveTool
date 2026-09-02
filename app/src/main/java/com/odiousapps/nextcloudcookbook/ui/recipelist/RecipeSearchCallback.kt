package com.odiousapps.nextcloudcookbook.ui.recipelist

import com.odiousapps.nextcloudcookbook.data.CategoryFilter
import com.odiousapps.nextcloudcookbook.data.RecipeFilter

interface RecipeSearchCallback {
    public fun searchRecipes(filter: RecipeFilter)
    public fun searchCategory(filter: CategoryFilter)
    public fun showSortSelector()
}