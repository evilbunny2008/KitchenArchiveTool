package de.micmun.android.nextcloudcookbook.ui.recipelist

import de.micmun.android.nextcloudcookbook.data.CategoryFilter
import de.micmun.android.nextcloudcookbook.data.RecipeFilter

interface RecipeSearchCallback {
    public fun searchRecipes(filter: RecipeFilter)
    public fun searchCategory(filter: CategoryFilter)
    public fun showSortSelector()
}