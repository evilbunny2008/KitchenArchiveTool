/*
 * RecipeListAdapter.kt
 *
 * Copyright 2020 by MicMun
 */
package de.micmun.android.nextcloudcookbook.ui.recipelist

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import de.micmun.android.nextcloudcookbook.R
import de.micmun.android.nextcloudcookbook.databinding.RecipeListRowBinding
import de.micmun.android.nextcloudcookbook.db.DbRecipeRepository
import de.micmun.android.nextcloudcookbook.db.model.DbRecipePreview

/**
 * RecyclerViewAdapter for the list of recipes.
 *
 * @author MicMun
 * @version 1.5, 21.06.21
 */
class RecipeListAdapter(private val clickListener: RecipeListListener, private val repository: DbRecipeRepository) :
   ListAdapter<DbRecipePreview, RecipeListAdapter.RecipeViewHolder>(RECIPE_ITEM_CALLBACK) {

   companion object {
      private val RECIPE_ITEM_CALLBACK = object : DiffUtil.ItemCallback<DbRecipePreview>() {
         override fun areItemsTheSame(oldItem: DbRecipePreview, newItem: DbRecipePreview): Boolean =
            oldItem.id == newItem.id

         override fun areContentsTheSame(oldItem: DbRecipePreview, newItem: DbRecipePreview): Boolean =
            oldItem.name == newItem.name
      }
   }

   override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecipeViewHolder {
      return RecipeViewHolder.from(parent)
   }

   override fun onBindViewHolder(holder: RecipeViewHolder, position: Int) {
      val recipe = currentList[position]
      holder.bind(clickListener, repository, recipe)
   }

   override fun getItemCount(): Int {
      return currentList.size
   }

   class RecipeViewHolder private constructor(private val binding: RecipeListRowBinding) :
      RecyclerView.ViewHolder(binding.root) {
      /**
       * Binds the data to the views.
       *
       * @param recipe Recipe data.
       */
      fun bind(clickListener: RecipeListListener, repository: DbRecipeRepository, recipe: DbRecipePreview) {
         binding.recipe = recipe
         binding.clickListener = clickListener
         val starSwitcher = binding.recipeOverviewStar
         if (recipe.starred && starSwitcher.currentView.id == R.id.recipeOverviewStarOff) {
            starSwitcher.showNext()
         } else if (!recipe.starred && starSwitcher.currentView.id == R.id.recipeOverviewStarOn) {
            starSwitcher.showNext()
         }
         starSwitcher.setOnClickListener { _ ->
            starSwitcher.showNext()
            repository.updateStar(recipe.id, starSwitcher.currentView.id == R.id.recipeOverviewStarOn)
         }
         binding.executePendingBindings()
         binding.recipeOverviewImage.clipToOutline = true
      }

      companion object {
         fun from(parent: ViewGroup): RecipeViewHolder {
            val layoutInflater = LayoutInflater.from(parent.context)
            val binding = RecipeListRowBinding.inflate(layoutInflater, parent, false)
            return RecipeViewHolder(binding)
         }
      }
   }
}

class RecipeListListener(val clickListener: (recipeId: Long) -> Unit) {
   fun onClick(recipe: DbRecipePreview) = clickListener(recipe.id)
}
