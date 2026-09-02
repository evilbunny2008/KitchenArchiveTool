/*
 * DbRecipe.kt
 *
 * Copyright 2021 by MicMun
 */
package de.micmun.android.nextcloudcookbook.db.model

import androidx.room.*
import androidx.room.ForeignKey.Companion.CASCADE
import de.micmun.android.nextcloudcookbook.R.string.*

/**
 * Database entity for recipe.
 *
 * @author MicMun
 * @version 1.2, 23.11.21
 */
data class DbRecipe(
   @Embedded val recipeCore: DbRecipeCore,
   @Relation(parentColumn = "id", entityColumn = "recipeId")
   val review: List<DbReview>? = null,
   @Relation(parentColumn = "id", entityColumn = "recipeId")
   val tool: List<DbTool>? = null,
   @Relation(parentColumn = "id", entityColumn = "recipeId")
   val recipeIngredient: List<DbIngredient>? = null,
   @Relation(parentColumn = "id", entityColumn = "recipeId")
   val recipeInstructions: List<DbInstruction>? = null,
   @Relation(
      parentColumn = "id",
      entity = DbKeyword::class,
      entityColumn = "id",
      associateBy = Junction(
         DbRecipeKeywordRelation::class,
         parentColumn = "recipeId",
         entityColumn = "keywordId"
      ),
   )
   val keywords: List<DbKeyword>? = null,
) {
   override fun equals(other: Any?): Boolean {
      return if (other == null || other !is DbRecipe) false else recipeCore.name == other.recipeCore.name
   }

   override fun hashCode(): Int {
      return recipeCore.name.hashCode()
   }
}

@Entity(tableName = "recipes")
data class DbRecipeCore(
   @PrimaryKey(autoGenerate = true) var id: Long = 0L,

   @Embedded(prefix = "agrat_") val aggregateRating: DbAggregateRating?,
   @Embedded(prefix = "author_") val author: DbAuthor?,
   val cookTime: String = "",
   val dateCreated: String = "",
   val dateModified: String = "",
   val datePublished: String = "",
   val description: String = "",
   val image: String = "",
   val thumbImageUrl: String = "",
   val fullImageUrl: String = "",
   val name: String,
   @Embedded(prefix = "nutrition_") val nutrition: DbNutrition?,
   val prepTime: String = "",
   val recipeCategory: String = "",
   val recipeYield: String = "",
   val totalTime: String = "",
   val url: String = "",
   val yield: String = "",
   val estimatedCost: String = "",
   val starred: Boolean = false,
   @Embedded(prefix = "fs_") val fileSystem: DbFilesystemRecipe,
)

data class DbFilesystemRecipe(
   val filePath: String,
   val lastModified: Long = 0,
)

data class DbAggregateRating(
   val type: String,
   val ratingCount: String,
   val ratingValue: String,
   val reviewCount: String
)

data class DbAuthor(
   val type: String = "Person",
   val name: String
)

data class DbNutrition(
   val type: String,
   val calories: String = "",
   val carbohydrateContent: String = "",
   val cholesterolContent: String = "",
   val fatContent: String = "",
   val fiberContent: String = "",
   val proteinContent: String = "",
   val sodiumContent: String = "",
   val saturatedFatContent: String = "",
   val servingSize: String = "",
   val sugarContent: String = "",
   val transFatContent: String = "",
   val unsaturatedFatContent: String = ""
) {
   /**
    * Returns a map with the nutritions which are set.
    *
    * @return Map with nutritions which are set.
    */
   fun toMap(): Map<Int, String> {
      val map = mutableMapOf<Int, String>()

      if (calories.isNotEmpty())
         map[nutrition_calories] = calories
      if (carbohydrateContent.isNotEmpty())
         map[nutrition_carbohydrate_content] = carbohydrateContent
      if (cholesterolContent.isNotEmpty())
         map[nutrition_cholesterol_content] = cholesterolContent
      if (fatContent.isNotEmpty())
         map[nutrition_fat_content] = fatContent
      if (fiberContent.isNotEmpty())
         map[nutrition_fiberContent] = fiberContent
      if (proteinContent.isNotEmpty())
         map[nutrition_protein_content] = proteinContent
      if (saturatedFatContent.isNotEmpty())
         map[nutrition_saturated_fat_content] = saturatedFatContent
      if (servingSize.isNotEmpty())
         map[nutrition_serving_size] = servingSize
      if (sodiumContent.isNotEmpty())
         map[nutrition_sodium_content] = sodiumContent
      if (sugarContent.isNotEmpty())
         map[nutrition_sugar_content] = sugarContent
      if (transFatContent.isNotEmpty())
         map[nutrition_trans_fat_content] = transFatContent
      if (unsaturatedFatContent.isNotEmpty())
         map[nutrition_unsaturated_fat_content] = unsaturatedFatContent

      return map
   }
}

@Entity(
   tableName = "reviews", foreignKeys = [ForeignKey(
      entity = DbRecipeCore::class,
      parentColumns = ["id"],
      childColumns = ["recipeId"],
      onDelete = CASCADE
   )],
   indices = [Index("recipeId")]
)
data class DbReview(
   @PrimaryKey(autoGenerate = true)
   val id: Long = 0L,
   var recipeId: Long = -1,
   val type: String = "",
   @Embedded(prefix = "author_") val author: DbAuthor?,
   val dateCreated: String,
   val description: String,
   @Embedded(prefix = "itrev_") val itemReviewed: DbItemReviewed? = null
)

data class DbItemReviewed(
   val type: String = "",
   val name: String
)

@Entity(
   tableName = "tools", foreignKeys = [ForeignKey(
      entity = DbRecipeCore::class,
      parentColumns = ["id"],
      childColumns = ["recipeId"],
      onDelete = CASCADE
   )],
   indices = [Index("recipeId")]
)
data class DbTool(
   @PrimaryKey(autoGenerate = true)
   val id: Long = 0L,
   var recipeId: Long = -1,
   val tool: String
)

@Entity(
   tableName = "ingredients", foreignKeys = [ForeignKey(
      entity = DbRecipeCore::class,
      parentColumns = ["id"],
      childColumns = ["recipeId"],
      onDelete = CASCADE
   )],
   indices = [Index("recipeId")]
)
data class DbIngredient(
   @PrimaryKey(autoGenerate = true)
   val id: Long = 0L,
   var recipeId: Long = -1,
   val ingredient: String
)

@Entity(
   tableName = "instructions", foreignKeys = [ForeignKey(
      entity = DbRecipeCore::class,
      parentColumns = ["id"],
      childColumns = ["recipeId"],
      onDelete = CASCADE
   )],
   indices = [Index("recipeId")]
)
data class DbInstruction(
   @PrimaryKey(autoGenerate = true)
   val id: Long = 0L,
   var recipeId: Long = -1,
   val instruction: String
)

@Entity(tableName = "keywords", indices = [Index(value = ["keyword"], unique = true)])
data class DbKeyword(
   @PrimaryKey(autoGenerate = true)
   val id: Long = 0L,
   val keyword: String
)

@Entity(
   tableName = "recipeXKeywords", primaryKeys = ["recipeId", "keywordId"],
   indices = [Index(value = ["keywordId"])],
   foreignKeys = [ForeignKey(
      entity = DbRecipeCore::class,
      parentColumns = ["id"],
      childColumns = ["recipeId"],
      onDelete = CASCADE
   ),
      ForeignKey(
         entity = DbKeyword::class,
         parentColumns = ["id"],
         childColumns = ["keywordId"],
         onDelete = CASCADE
      )]
)
data class DbRecipeKeywordRelation(
   var recipeId: Long = -1,
   var keywordId: Long = -1
) {
   @Ignore
   constructor() : this(-1, -1)
}
