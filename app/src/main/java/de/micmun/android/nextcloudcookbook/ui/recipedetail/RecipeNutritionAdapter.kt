/*
 * RecipeNutritionAdapter.kt
 *
 * Copyright 2020 by MicMun
 */
package de.micmun.android.nextcloudcookbook.ui.recipedetail

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import de.micmun.android.nextcloudcookbook.databinding.NutritionItemBinding

/**
 * Adapter for nutritions data.
 *
 * @author MicMun
 * @version 1.0, 25.06.20
 */
class RecipeNutritionAdapter(private val nutritionList: List<String>) :
   RecyclerView.Adapter<RecipeNutritionAdapter.NutritionViewHolder>() {

   override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NutritionViewHolder {
      return NutritionViewHolder.from(parent)
   }

   override fun getItemCount() = nutritionList.size

   override fun onBindViewHolder(holder: NutritionViewHolder, position: Int) {
      val nutrition = nutritionList[position]
      holder.bind(nutrition)
   }

   class NutritionViewHolder private constructor(private val binding: NutritionItemBinding) :
      RecyclerView.ViewHolder(binding.root) {
      fun bind(nutrition: String) {
         binding.nutrition = nutrition
         binding.executePendingBindings()
      }

      companion object {
         fun from(parent: ViewGroup): NutritionViewHolder {
            val layoutInflater = LayoutInflater.from(parent.context)
            val binding = NutritionItemBinding.inflate(layoutInflater, parent, false)
            return NutritionViewHolder(binding)
         }
      }
   }
}
