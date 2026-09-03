/*
 * BindingUtils.kt
 *
 * Copyright 2020 by MicMun
 */
@file:Suppress("unused")

package com.odiousapps.kat.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.databinding.BindingAdapter
import com.odiousapps.kat.R
import com.odiousapps.kat.db.model.DbRecipe
import com.odiousapps.kat.db.model.DbRecipePreview
import com.odiousapps.kat.util.DurationUtils
import com.odiousapps.kat.util.ImageHelper.setImageURIAsync
import com.odiousapps.kat.util.ImageHelper.toImageUri
import java.util.stream.Collectors

/**
 * Utilities for binding data to view.
 *
 * @author MicMun
 * @version 2.6, 23.04.22
 */


// Overview list
@BindingAdapter("recipeImage")
fun ImageView.setRecipeImage(item: DbRecipePreview?) {
   setImageURIAsync(item?.thumbImageUrl.toImageUri(context))
}

@BindingAdapter("recipeName")
fun TextView.setRecipeName(item: DbRecipePreview?) {
   item?.let { text = it.name }
}

@BindingAdapter("recipeDescription")
fun TextView.setRecipeDesc(item: DbRecipe?) {
   item?.let { text = it.recipeCore.description }
}

@BindingAdapter("recipePreviewDescription")
fun TextView.setRecipeDesc(item: DbRecipePreview?) {
   item?.let { text = it.description }
}

// Detail view
@BindingAdapter("recipeHeaderImage")
fun ImageView.setRecipeHeaderImage(item: DbRecipe?) {
   setImageURIAsync(item?.recipeCore?.fullImageUrl.toImageUri(context)) {
      setPadding(0, 0, 0, 0)
   }
}

@BindingAdapter("recipePublishedDate")
fun TextView.setPublishedDate(item: DbRecipe?) {
   item?.let {
      val date = it.recipeCore.datePublished.ifEmpty { "-" }
      text = resources.getString(R.string.text_date_published, date)
   }
}

@BindingAdapter("recipePrepTime")
fun TextView.setPrepTime(item: DbRecipe?) {
   item?.let {
      text = if (it.recipeCore.prepTime.isEmpty()) "" else DurationUtils.formatStringToDuration(
         it.recipeCore.prepTime)
   }
}

@SuppressLint("SetTextI18n")
@BindingAdapter("recipeCookTime")
fun TextView.setCookTime(item: DbRecipe?) {
   item?.let {
      text = if (it.recipeCore.cookTime.isEmpty()) {
         ""
      } else {
         DurationUtils.formatStringToDuration(it.recipeCore.cookTime)
      }

      if (text.isNotEmpty()) {
         // timer icon
         setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_timer, 0, 0, 0)
         // tooltip
         tooltipText = context.getString(R.string.cooktime_tooltip)
      } else {
         // was setBackgroundColor(android.R.color.transparent) -- that passed a
         // resource ID where an actual ARGB colour int was expected. Colour.TRANSPARENT
         // is the resolved value directly, with no resource lookup needed.
         setBackgroundColor(Color.TRANSPARENT)
      }
   }
}

@BindingAdapter("recipeTotalTime")
fun TextView.setTotalTime(item: DbRecipe?) {
   item?.let {
      text = if (it.recipeCore.totalTime.isEmpty()) "" else DurationUtils.formatStringToDuration(
         it.recipeCore.totalTime)
   }
}

@BindingAdapter("recipeCategories")
fun TextView.setRecipeCategories(item: DbRecipe?) {
   item?.let {
      val categories = it.recipeCore.recipeCategory.ifEmpty { resources.getString(R.string.text_uncategorized) }
      @Suppress("DEPRECATION")
      text = categories
   }
}

@BindingAdapter("keywords")
fun TextView.setKeywords(item: DbRecipe?) {
   item?.let { recipe ->
      val keywords = if (recipe.keywords.isNullOrEmpty())
         resources.getString(R.string.text_no_keywords)
      else
         recipe.keywords.joinToString(transform = { kw -> kw.keyword })
      @Suppress("DEPRECATION")
      text = keywords
   }
}

@BindingAdapter("recipeAuthor")
fun TextView.setAuthor(item: DbRecipe?) {
   item?.let {
      if (it.recipeCore.author == null) {
         visibility = View.GONE
      } else {
         text = it.recipeCore.author.name
         visibility = View.VISIBLE
      }
   }
}

@BindingAdapter("url")
fun TextView.setUrl(item: DbRecipe?) {
   item?.let {
      if (it.recipeCore.url.isEmpty()) {
         visibility = View.GONE
      } else {
         text = it.recipeCore.url
         visibility = View.VISIBLE
      }
   }
}

@BindingAdapter("recipeYield")
fun TextView.setRecipeYield(item: DbRecipe?) {
   item?.let {
      if (it.recipeCore.recipeYield.isEmpty()) {
         visibility = View.GONE
      } else {
         @Suppress("DEPRECATION")
         text = it.recipeCore.recipeYield
         visibility = View.VISIBLE
      }
   }
}

@BindingAdapter("recipeTools")
fun TextView.setTools(item: DbRecipe?) {
   item?.let { recipe ->
      if (recipe.tool.isNullOrEmpty()) {
         visibility = View.GONE
      } else {
         val tools = recipe.tool.stream().map { it.tool }.collect(Collectors.toList()).joinToString(", ")

         @Suppress("DEPRECATION")
         text = tools
      }
   }
}

// cooking timer
/**
 * Sets the top text for cooktimer text.
 *
 * @param item Recipe data.
 */
@BindingAdapter("topTextTimer")
fun TextView.setTopText(item: DbRecipe?) {
   item?.let {
      text = context.getString(R.string.cooktime_top_text, it.recipeCore.name,
                               DurationUtils.formatStringToDuration(it.recipeCore.cookTime))
   }
}
