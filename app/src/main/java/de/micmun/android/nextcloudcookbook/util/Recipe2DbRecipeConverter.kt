/*
 * Recipe2DbRecipeConverter.kt
 *
 * Copyright 2021 by MicMun
 */
package de.micmun.android.nextcloudcookbook.util

import de.micmun.android.nextcloudcookbook.db.model.*
import de.micmun.android.nextcloudcookbook.json.model.*
import java.util.stream.Collectors

/**
 * Converter for recipe into database recipe pojo.
 *
 * @author MicMun
 * @version 1.4, 23.11.21
 */
class Recipe2DbRecipeConverter(private val recipe: Recipe) {
   fun convert(): DbRecipe {
      val core = DbRecipeCore(
         cookTime = cns(recipe.cookTime),
         dateCreated = cns(recipe.dateCreated),
         dateModified = cns(recipe.dateModified),
         datePublished = cns(recipe.datePublished),
         description = cns(recipe.description),
         image = cns(recipe.image),
         thumbImageUrl = cns(recipe.thumbImageUrl),
         fullImageUrl = cns(recipe.fullImageUrl),
         name = recipe.name,
         prepTime = cns(recipe.prepTime),
         recipeCategory = recipe.recipeCategory?.joinToString(",") ?: "",
         recipeYield = cns(recipe.recipeYield),
         totalTime = cns(recipe.totalTime),
         url = cns(recipe.url),
         yield = cns(recipe.yield),
         estimatedCost = cns(recipe.estimatedCost),
         aggregateRating = getAggregatRating(recipe.aggregateRating),
         author = getAuthor(recipe.author),
         nutrition = getNutrition(recipe.nutrition),
         fileSystem = DbFilesystemRecipe(
            filePath = recipe.fileLocation,
            lastModified = recipe.fileModified),
      )

      return DbRecipe(
         core,
         tool = getTools(recipe.tool),
         review = getReview(recipe.review),
         recipeIngredient = getIngredients(recipe.recipeIngredient),
         recipeInstructions = getInstructions(recipe.recipeInstructions),
         keywords = getKeywords(recipe.keywords)
      )
   }

   private fun cns(value: String?): String {
      return value ?: ""
   }

   private fun getAggregatRating(aggregateRating: AggregateRating?): DbAggregateRating? {
      var aggRat: DbAggregateRating? = null

      if (aggregateRating != null) {
         aggRat = DbAggregateRating(
            cns(aggregateRating.type),
            cns(aggregateRating.ratingCount),
            cns(aggregateRating.ratingValue),
            cns(aggregateRating.reviewCount)
         )
      }

      return aggRat
   }

   private fun getAuthor(author: Author?): DbAuthor? {
      var value: DbAuthor? = null

      if (author != null) {
         value = DbAuthor(cns(author.type), cns(author.name))
      }

      return value
   }

   private fun getNutrition(nutrition: Nutrition?): DbNutrition? {
      var value: DbNutrition? = null

      if (nutrition != null) {
         value = DbNutrition(
            cns(nutrition.type),
            cns(nutrition.calories),
            cns(nutrition.carbohydrateContent),
            cns(nutrition.cholesterolContent),
            cns(nutrition.fatContent),
            cns(nutrition.fiberContent),
            cns(nutrition.proteinContent),
            cns(nutrition.sodiumContent),
            cns(nutrition.saturatedFatContent),
            cns(nutrition.servingSize),
            cns(nutrition.sugarContent),
            cns(nutrition.transFatContent),
            cns(nutrition.unsaturatedFatContent)
         )
      }

      return value
   }

   private fun getReview(reviews: List<Review>?): List<DbReview>? {
      var value: List<DbReview>? = null

      if (reviews != null) {
         value = reviews.stream()
            .map { t ->
               DbReview(
                  type = cns(t.type),
                  dateCreated = cns(t.dateCreated),
                  description = cns(t.description),
                  author = getAuthor(t.author),
                  itemReviewed = getItemReviewed(t.itemReviewed)
               )
            }
            .collect(Collectors.toList())
      }

      return value
   }

   private fun getItemReviewed(itemReviewed: ItemReviewed?): DbItemReviewed? {
      var value: DbItemReviewed? = null

      if (itemReviewed != null) {
         value = DbItemReviewed(cns(itemReviewed.type), cns(itemReviewed.name))
      }

      return value
   }

   private fun getTools(tools: List<String>?): List<DbTool>? {
      var value: List<DbTool>? = null

      if (tools != null) {
         value = tools.stream()
            .map { DbTool(tool = it) }
            .collect(Collectors.toList())
      }

      return value
   }

   private fun getIngredients(ingredients: List<String>?): List<DbIngredient>? {
      var value: List<DbIngredient>? = null

      if (ingredients != null) {
         value = ingredients.stream()
            .map { DbIngredient(ingredient = it) }
            .collect(Collectors.toList())
      }

      return value
   }

   private fun getInstructions(instructions: List<String>?): List<DbInstruction>? {
      var value: List<DbInstruction>? = null

      if (instructions != null) {
         value = instructions.stream()
            .map { DbInstruction(instruction = it) }
            .collect(Collectors.toList())
      }

      return value
   }

   private fun getKeywords(keywords: List<String>?): List<DbKeyword> {
      var value: List<DbKeyword> = emptyList()

      if (keywords != null) {
         value = keywords.stream()
            .map { DbKeyword(keyword = it) }
            .collect(Collectors.toList())
      }

      return value
   }
}
