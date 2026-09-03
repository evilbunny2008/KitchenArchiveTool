/*
 * CookTimeClickListener.kt
 *
 * Copyright 2021 by MicMun
 */
package com.odiousapps.kat.ui.recipedetail

import com.odiousapps.kat.db.model.DbRecipe

/**
 * ClickListener for click on Cooktime.
 *
 * @author MicMun
 * @version 1.0, 24.07.21
 */
interface CookTimeClickListener {
   /**
    * Handle click on the cook time.
    *
    * @param recipe with the data of recipe.
    */
   fun onClick(recipe: DbRecipe)
}
