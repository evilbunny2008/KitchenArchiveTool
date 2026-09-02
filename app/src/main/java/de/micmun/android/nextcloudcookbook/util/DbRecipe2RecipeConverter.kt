/*
 * DbRecipe2RecipeConverter.kt
 *
 * Copyright 2021 by MicMun
 */
package de.micmun.android.nextcloudcookbook.util

import de.micmun.android.nextcloudcookbook.db.model.*
import de.micmun.android.nextcloudcookbook.json.model.*
import java.util.stream.Collectors

/**
 * Converter for database recipe pojo to recipe.
 *
 * @author MicMun
 * @version 1.1, 11.07.21
 */
class DbRecipe2RecipeConverter(private val recipe: DbRecipe) {
   fun convert(): Recipe {
      return Recipe(
         name = recipe.recipeCore.name,
         description = recipe.recipeCore.description,
         dateCreated = recipe.recipeCore.dateCreated,
         dateModified = recipe.recipeCore.dateModified,
         datePublished = recipe.recipeCore.datePublished,
         prepTime = recipe.recipeCore.prepTime,
         cookTime = recipe.recipeCore.cookTime,
         totalTime = recipe.recipeCore.totalTime,
         image = recipe.recipeCore.image,
         thumbImageUrl = recipe.recipeCore.thumbImageUrl,
         fullImageUrl = recipe.recipeCore.fullImageUrl,
//         keywords = recipe.keywords?.joinToString(separator = ",",
//                                                  transform = {kw -> kw.keyword}) ?: "",
         keywords = getKeywords(recipe.keywords),
         recipeCategory = recipe.recipeCore.recipeCategory.split(",").toList(),
         url = recipe.recipeCore.url,
         recipeYield = recipe.recipeCore.recipeYield,
         yield = recipe.recipeCore.yield,
         estimatedCost = recipe.recipeCore.estimatedCost,
         author = getAuthor(recipe.recipeCore.author),
         aggregateRating = getAggregatRating(recipe.recipeCore.aggregateRating),
         nutrition = getNutrition(recipe.recipeCore.nutrition),
         tool = getTools(recipe.tool),
         recipeIngredient = getIngredients(recipe.recipeIngredient),
         recipeInstructions = getInstructions(recipe.recipeInstructions),
         review = getReview(recipe.review),
         fileLocation = recipe.recipeCore.fileSystem.filePath,
         fileModified = recipe.recipeCore.fileSystem.lastModified,
      )
   }

   private fun getAggregatRating(aggregateRating: DbAggregateRating?): AggregateRating? {
      var aggRat: AggregateRating? = null

      if (aggregateRating != null) {
         aggRat = AggregateRating(
            aggregateRating.type,
            aggregateRating.ratingCount,
            aggregateRating.ratingValue,
            aggregateRating.reviewCount
         )
      }

      return aggRat
   }

   private fun getAuthor(author: DbAuthor?): Author? {
      var value: Author? = null

      if (author != null) {
         value = Author(author.type, author.name)
      }

      return value
   }

   private fun getNutrition(nutrition: DbNutrition?): Nutrition? {
      var value: Nutrition? = null

      if (nutrition != null) {
         value = Nutrition(
            nutrition.type,
            nutrition.calories,
            nutrition.carbohydrateContent,
            nutrition.cholesterolContent,
            nutrition.fatContent,
            nutrition.fiberContent,
            nutrition.proteinContent,
            nutrition.sodiumContent
         )
      }

      return value
   }

   private fun getReview(reviews: List<DbReview>?): List<Review>? {
      var value: List<Review>? = null

      if (reviews != null) {
         if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            value = reviews.stream()
               .map { t ->
                  Review(
                     type = t.type,
                     dateCreated = t.dateCreated,
                     description = t.description,
                     author = getAuthor(t.author),
                     itemReviewed = getItemReviewed(t.itemReviewed)
                  )
               }
               .collect(Collectors.toList())
         } else {
            value = mutableListOf()
            reviews.forEach { t ->
               val dbReview = Review(
                  type = t.type,
                  dateCreated = t.dateCreated,
                  description = t.description,
                  author = getAuthor(t.author),
                  itemReviewed = getItemReviewed(t.itemReviewed)
               )
               value.add(dbReview)
            }
         }
      }

      return value
   }

   private fun getItemReviewed(itemReviewed: DbItemReviewed?): ItemReviewed? {
      var value: ItemReviewed? = null

      if (itemReviewed != null) {
         value = ItemReviewed(itemReviewed.type, itemReviewed.name)
      }

      return value
   }

   private fun getTools(tools: List<DbTool>?): List<String>? {
      var value: List<String>? = null

      if (tools != null) {
         if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            value = tools.stream()
               .map { it.tool }
               .collect(Collectors.toList())
         } else {
            value = mutableListOf()
            tools.forEach {
               value.add(it.tool)
            }
         }
      }

      return value
   }

   private fun getIngredients(ingredients: List<DbIngredient>?): List<String>? {
      var value: List<String>? = null

      if (ingredients != null) {
         if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            value = ingredients.stream()
               .map { it.ingredient }
               .collect(Collectors.toList())
         } else {
            value = mutableListOf()
            ingredients.forEach { t ->
               value.add(t.ingredient)
            }
         }
      }

      return value
   }

   private fun getInstructions(instructions: List<DbInstruction>?): List<String>? {
      var value: List<String>? = null

      if (instructions != null) {
         value = instructions.stream()
            .map { it.instruction }
            .collect(Collectors.toList())
      }

      return value
   }

   private fun getKeywords(keywords: List<DbKeyword>?): List<String> {
      return keywords?.stream()
                ?.map {
                   it.keyword
                }
                ?.collect(Collectors.toList()) ?: emptyList()
   }
}
