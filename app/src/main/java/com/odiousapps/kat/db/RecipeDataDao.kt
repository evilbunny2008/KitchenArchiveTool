/*
 * RecipeDataDao.kt
 *
 * Copyright 2021 by MicMun
 */
package com.odiousapps.kat.db

import androidx.lifecycle.LiveData
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteQuery
import com.odiousapps.kat.db.model.*
import kotlinx.coroutines.flow.Flow

/**
 * Interface for database operations.
 *
 * @author MicMun
 * @version 1.2, 28.08.21
 */
@Dao
interface RecipeDataDao {
   @Transaction
   @Query("SELECT DISTINCT ${DbRecipePreview.DBFIELDS} FROM recipes WHERE fs_filePath LIKE :dirPrefix ESCAPE '\\' ORDER BY starred DESC, LOWER(name) ASC")
   fun getAllRecipePreviews(dirPrefix: String): Flow<List<DbRecipePreview>>

   @Transaction
   @Query(
      "SELECT DISTINCT ${DbRecipePreview.DBFIELDS} FROM recipes WHERE fs_filePath LIKE :dirPrefix ESCAPE '\\' ORDER BY starred DESC, " +
            "CASE WHEN :isAsc = 1 THEN LOWER(name) END ASC," +
            "CASE WHEN :isAsc = 0 THEN LOWER(name) END DESC"
   )
   fun sortByName(isAsc: Boolean, dirPrefix: String): Flow<List<DbRecipePreview>>

   @Transaction
   @Query(
      "SELECT DISTINCT ${DbRecipePreview.DBFIELDS} FROM recipes WHERE fs_filePath LIKE :dirPrefix ESCAPE '\\' ORDER BY starred DESC, " +
            "CASE WHEN :isAsc = 1 THEN COALESCE(NULLIF(datePublished, ''), dateCreated) END ASC," +
            "CASE WHEN :isAsc = 0 THEN COALESCE(NULLIF(datePublished, ''), dateCreated) END DESC"
   )
   fun sortByDate(isAsc: Boolean, dirPrefix: String): Flow<List<DbRecipePreview>>

   @Transaction
   @Query(
      "SELECT DISTINCT ${DbRecipePreview.DBFIELDS} FROM recipes WHERE fs_filePath LIKE :dirPrefix ESCAPE '\\' ORDER BY starred DESC, " +
            "CASE WHEN :isAsc = 1 THEN totalTime END ASC," +
            "CASE WHEN :isAsc = 0 THEN totalTime END DESC"
   )
   fun sortByTotalTime(isAsc: Boolean, dirPrefix: String): Flow<List<DbRecipePreview>>

   @Transaction
   @Query("SELECT * FROM recipes WHERE id = :id")
   fun getById(id: Long): LiveData<DbRecipe?>

   /**
    * Same lookup as [getById], but a plain synchronous return instead of
    * LiveData -- for one-shot background use (e.g. resolving a recipe's
    * fileSystem.filePath for the copy-to-account long-press menu) where
    * setting up a LiveData observer would be overkill.
    */
   @Transaction
   @Query("SELECT * FROM recipes WHERE id = :id")
   fun getByIdSync(id: Long): DbRecipe?

   @Transaction
   @Query("SELECT * FROM recipes WHERE name = :n")
   fun findByName(n: String): DbRecipe?

   @Transaction
   @Query("SELECT * FROM recipes WHERE name = :n AND fs_filePath LIKE :dirPrefix ESCAPE '\\'")
   fun findByNameInDir(n: String, dirPrefix: String): DbRecipe?

   /**
    * Finds duplicate recipe rows within the given account's directory --
    * every row sharing a name with another, except the most recently
    * inserted (highest id) one, which is kept. See DbRecipeRepository's
    * deleteDuplicates() doc comment for why duplicates can occur.
    */
   @Transaction
   @Query(
      "SELECT * FROM recipes WHERE fs_filePath LIKE :dirPrefix ESCAPE '\\' " +
            "AND id NOT IN (SELECT MAX(id) FROM recipes WHERE fs_filePath LIKE :dirPrefix ESCAPE '\\' GROUP BY name)"
   )
   fun findDuplicates(dirPrefix: String): List<DbRecipe>

   @Transaction
   @Query("SELECT fs_filePath AS filePath, fs_lastModified AS lastModified FROM recipes")
   fun getAllFileInfos(): List<DbFilesystemRecipe>

   @Transaction
   @Query("SELECT * FROM keywords WHERE keyword IN(:n)")
   fun findKeywords(n: List<String>): List<DbKeyword>

   @Transaction
   @Query("SELECT * FROM recipeXKeywords WHERE recipeId = :recipeId")
   fun findKeywordRefsByRecipeId(recipeId: Long): List<DbRecipeKeywordRelation>

   @Transaction
   @Query("SELECT * FROM keywords ORDER BY keyword")
   fun getAllKeywords(): Flow<List<DbKeyword>>

   @Transaction
   @RawQuery(
      observedEntities = [DbRecipeCore::class, DbInstruction::class, DbIngredient::class, DbTool::class,
         DbReview::class, DbKeyword::class, DbRecipeKeywordRelation::class]
   )
   fun filterRecipes(query: SupportSQLiteQuery): Flow<List<DbRecipePreview>>

   @Transaction
   @Query("SELECT DISTINCT recipeCategory FROM recipes WHERE recipeCategory != '' ORDER BY recipeCategory")
   fun getCategories(): Flow<List<String>>

   @Insert
   fun insert(recipe: DbRecipeCore): Long

   @Insert
   fun insertTools(tool: List<DbTool>)

   @Insert
   fun insertReviews(review: List<DbReview>)

   @Insert
   fun insertInstructions(instruction: List<DbInstruction>)

   @Insert
   fun insertIngredients(ingredient: List<DbIngredient>)

   @Insert(onConflict = OnConflictStrategy.IGNORE)
   fun insertKeywords(keywords: List<DbKeyword>)

   @Insert(onConflict = OnConflictStrategy.IGNORE)
   fun insertKeywordRefs(keywords: List<DbRecipeKeywordRelation>)

   @Update
   fun update(recipe: DbRecipeCore)

   @Update(entity = DbRecipeCore::class)
   fun updateStar(recipe: DbRecipeStar)

   @Update
   fun updateTools(tool: List<DbTool>)

   @Update
   fun updateReviews(review: List<DbReview>)

   @Update
   fun updateInstructions(instruction: List<DbInstruction>)

   @Update
   fun updateIngredients(ingredient: List<DbIngredient>)

   @Delete
   fun delete(recipe: DbRecipeCore)

   @Delete
   fun deleteTools(tool: List<DbTool>)

   @Delete
   fun deleteReviews(review: List<DbReview>)

   @Delete
   fun deleteInstructions(instruction: List<DbInstruction>)

   @Delete
   fun deleteIngredients(ingredient: List<DbIngredient>)

   @Delete
   fun deleteKeywordRelations(keywords: List<DbRecipeKeywordRelation>)

   @Query("DELETE FROM keywords")
   fun deleteAllKeywords()

   @Query("DELETE FROM recipeXKeywords")
   fun deleteAllKeywordRelations()
}
