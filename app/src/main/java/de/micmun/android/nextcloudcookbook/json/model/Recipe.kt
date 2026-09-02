/*
 * Recipe.kt
 *
 * Copyright 2021 by MicMun
 */
package de.micmun.android.nextcloudcookbook.json.model

import de.micmun.android.nextcloudcookbook.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Generated recipe data classes..
 *
 * @author MicMun
 * @version 1.2, 23.11.21
 */
@Serializable
data class Recipe(
   @SerialName("@context")
   val context: String = "http://schema.org",
   @SerialName("@type")
   val type: String = "Recipe",
   val aggregateRating: AggregateRating? = null,
   @Serializable(with = AuthorSerializer::class)
   val author: Author? = null,
   val cookTime: String? = null,
   @Serializable(with = DateSerializer::class)
   val dateCreated: String? = null,
   @Serializable(with = DateSerializer::class)
   val dateModified: String? = null,
   @Serializable(with = DateSerializer::class)
   val datePublished: String? = null,
   val description: String? = null,
   @Serializable(with = ImageSerializier::class)
   var image: String? = "",
   @Transient
   var thumbImageUrl: String? = null,
   @Transient
   var fullImageUrl: String? = null,
   @Serializable(with = KeywordsSerializer::class)
   val keywords: List<String>? = null,
   val name: String = "",
   @Serializable(with = NutritionSerializer::class)
   val nutrition: Nutrition? = null,
   val prepTime: String? = null,
   @Serializable(with = StringListSerializer::class)
   var recipeCategory: List<String>? = null,
   @Serializable(with = StringListSerializer::class)
   val recipeIngredient: List<String>? = null,
   @Serializable(with = InstructionSerializer::class)
   val recipeInstructions: List<String>? = null,
   @Serializable(with = ListStringSerializer::class)
   val recipeYield: String? = null,
   @Transient
   val review: List<Review>? = null,
   @Serializable(with = StringListSerializer::class)
   val tool: List<String>? = null,
   val totalTime: String? = null,
   val url: String? = null,
   @Serializable(with = ListStringSerializer::class)
   val yield: String? = null,
   val estimatedCost: String? = null,
   @Transient
   val fileLocation: String = "",
   @Transient
   val fileModified: Long = 0,
)

@Serializable
data class AggregateRating(
   @SerialName("@type")
   val type: String? = null,
   @Serializable(with = ListStringSerializer::class)
   val ratingCount: String? = null,
   @Serializable(with = ListStringSerializer::class)
   val ratingValue: String? = null,
   @Serializable(with = ListStringSerializer::class)
   val reviewCount: String? = null
)

@Serializable
data class Author(
   @SerialName("@type")
   val type: String? = "Person",
   val name: String? = null
)

@Serializable
data class Nutrition(
   @SerialName("@type")
   val type: String? = null,
   val calories: String? = null,
   val carbohydrateContent: String? = null,
   val cholesterolContent: String? = null,
   val fatContent: String? = null,
   val fiberContent: String? = null,
   val proteinContent: String? = null,
   val sodiumContent: String? = null,
   val saturatedFatContent: String? = null,
   val servingSize: String? = null,
   val sugarContent: String? = null,
   val transFatContent: String? = null,
   val unsaturatedFatContent: String? = null
)

@Serializable
data class Review(
   @SerialName("@type")
   val type: String? = null,
   val author: Author? = null,
   val dateCreated: String? = null,
   val description: String? = null,
   val itemReviewed: ItemReviewed? = null
)

@Serializable
data class ItemReviewed(
   @SerialName("@type")
   val type: String? = null,
   val name: String? = null
)
