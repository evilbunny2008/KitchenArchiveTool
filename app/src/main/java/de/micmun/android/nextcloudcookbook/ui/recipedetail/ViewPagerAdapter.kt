/*
 * ViewPagerAdapter.kt
 *
 * Copyright 2020 by MicMun
 */
package de.micmun.android.nextcloudcookbook.ui.recipedetail

import android.content.res.Configuration
import android.content.res.Resources
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import de.micmun.android.nextcloudcookbook.R
import de.micmun.android.nextcloudcookbook.databinding.*
import de.micmun.android.nextcloudcookbook.db.model.DbNutrition
import de.micmun.android.nextcloudcookbook.db.model.DbRecipe

/**
 * Adapter for the ViewPager2 to present tabs.
 *
 * @author MicMun
 * @version 1.8, 24.07.21
 */
class ViewPagerAdapter(private val recipe: DbRecipe, private val orientation: Int,
                       private val cookTimeClickListener: CookTimeClickListener) :
   RecyclerView.Adapter<RecyclerView.ViewHolder>() {
   companion object {
      const val TYPE_INFO = 0
      const val TYPE_NUTRITIONS = 1
      const val TYPE_INGREDIENTS = 2
      const val TYPE_INSTRUCTIONS = 3
      const val TYPE_INGRED_AND_INSTRUCT = 4

      private fun getYield(recipe: DbRecipe): Float {
         return ("\\d+".toRegex().find(recipe.recipeCore.recipeYield)?.value ?: "1").toFloat().coerceAtLeast(1f)
      }
   }

   override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
      return when (viewType) {
         TYPE_INFO -> InfoViewHolder.from(parent)
         TYPE_INGREDIENTS -> IngredientsViewHolder.from(parent)
         TYPE_INSTRUCTIONS -> InstructionsViewHolder.from(parent)
         TYPE_NUTRITIONS -> NutritionsViewHolder.from(parent)
         TYPE_INGRED_AND_INSTRUCT -> IngredientsAndInstructionsViewHolder.from(parent)
         else -> InfoViewHolder.from(parent)
      }
   }

   override fun getItemCount(): Int {
      return if (orientation == Configuration.ORIENTATION_PORTRAIT) 4 else 3
   }

   override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
      when (getItemViewType(position)) {
         TYPE_INFO -> {
            val viewHolder = holder as InfoViewHolder
            viewHolder.bind(recipe, cookTimeClickListener)
         }
         TYPE_NUTRITIONS -> {
            val viewHolder = holder as NutritionsViewHolder
            viewHolder.bind(recipe)
         }
         TYPE_INGREDIENTS -> {
            val viewHolder = holder as IngredientsViewHolder
            viewHolder.bind(recipe)
         }
         TYPE_INSTRUCTIONS -> {
            val viewHolder = holder as InstructionsViewHolder
            viewHolder.bind(recipe)
         }
         TYPE_INGRED_AND_INSTRUCT -> {
            val viewHolder = holder as IngredientsAndInstructionsViewHolder
            viewHolder.bind(recipe)
         }
      }
   }

   override fun getItemViewType(position: Int): Int {
      return if (orientation == Configuration.ORIENTATION_LANDSCAPE && position > 1)
         TYPE_INGRED_AND_INSTRUCT
      else
         position
   }

   class InfoViewHolder private constructor(private val binding: TabInfosBinding) :
      RecyclerView.ViewHolder(binding.root) {
      /**
       * Binds the data to the views.
       *
       * @param recipe Recipe with its data.
       */
      fun bind(recipe: DbRecipe, cookTimeClickListener: CookTimeClickListener) {
         binding.recipe = recipe
         binding.clickListener = cookTimeClickListener
         binding.executePendingBindings()
      }

      companion object {
         fun from(parent: ViewGroup): InfoViewHolder {
            val layoutInflater = LayoutInflater.from(parent.context)
            val binding = TabInfosBinding.inflate(layoutInflater, parent, false)
            return InfoViewHolder(binding)
         }
      }
   }

   class IngredientsViewHolder private constructor(private val binding: TabIngredientsBinding) :
      RecyclerView.ViewHolder(binding.root) {
      /**
       * Binds the data to the views.
       *
       * @param recipe Recipe data.
       */
      fun bind(recipe: DbRecipe) {
         binding.ingredientsView.adapter =
            RecipeIngredientsAdapter(binding, getYield(recipe), recipe.recipeIngredient ?: emptyList())
         binding.executePendingBindings()
      }

      companion object {
         fun from(parent: ViewGroup): IngredientsViewHolder {
            val layoutInflater = LayoutInflater.from(parent.context)
            val binding = TabIngredientsBinding.inflate(layoutInflater, parent, false)
            return IngredientsViewHolder(binding)
         }
      }
   }

   class InstructionsViewHolder private constructor(private val binding: TabInstructionsBinding) :
      RecyclerView.ViewHolder(binding.root) {
      /**
       * Binds the data to the views.
       *
       * @param recipe Recipe data.
       */
      fun bind(recipe: DbRecipe) {
         binding.instructionsView.adapter = RecipeInstructionsAdapter(recipe.recipeInstructions ?: emptyList())
         if(recipe.recipeInstructions!=null) {
            if(recipe.recipeInstructions.isNotEmpty()){
               binding.labelNoInstructions.visibility = View.GONE
            }
         }
         val orientation = (binding.instructionsView.layoutManager as LinearLayoutManager).orientation
         val dividerItemDecoration = DividerItemDecoration(binding.instructionsView.context, orientation)
         binding.instructionsView.addItemDecoration(dividerItemDecoration)
         binding.executePendingBindings()
      }

      companion object {
         fun from(parent: ViewGroup): InstructionsViewHolder {
            val layoutInflater = LayoutInflater.from(parent.context)
            val binding = TabInstructionsBinding.inflate(layoutInflater, parent, false)
            return InstructionsViewHolder(binding)
         }
      }
   }

   class NutritionsViewHolder private constructor(private val binding: TabNutritionsBinding,
                                                  private val resources: Resources) :
      RecyclerView.ViewHolder(binding.root) {
      /**
       * Binds the data to the views.
       *
       * @param recipe Recipe data.
       */
      fun bind(recipe: DbRecipe) {
         val switcher = binding.nutritionSwitcher
         val nutritions = getNutritionList(recipe.recipeCore.nutrition)

         if (nutritions.isEmpty() && R.id.nutritionEmptyLayout == switcher.nextView.id) {
            switcher.showNext()
         } else {
            binding.nutritionList.adapter = RecipeNutritionAdapter(nutritions)
            binding.executePendingBindings()

            if (R.id.nutritionListLayout == switcher.nextView.id) {
               switcher.showNext()
            }
         }

         binding.executePendingBindings()
      }

      companion object {
         fun from(parent: ViewGroup): NutritionsViewHolder {
            val layoutInflater = LayoutInflater.from(parent.context)
            val binding = TabNutritionsBinding.inflate(layoutInflater, parent, false)
            return NutritionsViewHolder(binding, parent.resources)
         }
      }

      /**
       * Returns the list of nutritions or an empty list.
       *
       * @return list of nutritions or an empty list.
       */
      private fun getNutritionList(nutrition: DbNutrition?): List<String> {
         val nutritions = nutrition?.toMap()?.toList()?.map { p -> resources.getString(p.first, p.second) }
         if (nutritions.isNullOrEmpty()) {
            return emptyList()
         }
         return nutritions
      }
   }

   class IngredientsAndInstructionsViewHolder private constructor(private val binding: TabRecipeBinding) :
      RecyclerView.ViewHolder(binding.root) {
      fun bind(recipe: DbRecipe) {
         // ingredients
         binding.ingredientsInclude.ingredientsView.adapter =
            RecipeIngredientsAdapter(binding.ingredientsInclude, getYield(recipe),
                                     recipe.recipeIngredient ?: emptyList())

         // instructions
         binding.instructionsView.adapter = RecipeInstructionsAdapter(recipe.recipeInstructions ?: emptyList())
         val orientation = (binding.instructionsView.layoutManager as LinearLayoutManager).orientation
         val dividerItemDecoration = DividerItemDecoration(binding.instructionsView.context, orientation)
         binding.instructionsView.addItemDecoration(dividerItemDecoration)

         binding.executePendingBindings()
      }

      companion object {
         fun from(parent: ViewGroup): IngredientsAndInstructionsViewHolder {
            val layoutInflater = LayoutInflater.from(parent.context)
            val binding = TabRecipeBinding.inflate(layoutInflater, parent, false)
            return IngredientsAndInstructionsViewHolder(binding)
         }
      }
   }
}
