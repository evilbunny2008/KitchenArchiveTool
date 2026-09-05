/*
 * JsonRecipeRepository.kt
 *
 * Copyright 2021 by MicMun
 */
package com.odiousapps.kat.db

import android.app.Application
import androidx.sqlite.db.SimpleSQLiteQuery
import com.odiousapps.kat.data.RecipeFilter
import com.odiousapps.kat.data.SortValue
import com.odiousapps.kat.db.model.*
import kotlinx.coroutines.flow.Flow

/**
 * Repository for recipes.
 *
 * @author MicMun
 * @version 1.5, 28.08.21
 */
class DbRecipeRepository private constructor(application: Application) {
   private var mRecipeDao: RecipeDataDao = RecipeDatabase.getDatabase(application).recipeDataDao()

   // we prepend 'recipes.' to resolve name ambiguities (e.g. column 'id')
   private val dbPreviewFields = DbRecipePreview.DBFIELDS.split(", ").joinToString(", ") { "recipes.$it" }

   /**
    * Builds a SQL LIKE pattern matching any file under the given recipe
    * directory (each account has its own recipes/<accountName>/ folder --
    * see Sync.kt -- so this is what scopes every query below to only the
    * currently active account's recipes). '%' and '_' are SQL LIKE
    * wildcards, so any literal occurrence of either in the path itself
    * (e.g. an account name containing '_') is escaped, matched by the
    * corresponding ESCAPE '\' clause on each query using this.
    */
   private fun likePrefix(recipeDir: String): String {
      val escaped = recipeDir
         .removeSuffix("/")
         .replace("\\", "\\\\")
         .replace("%", "\\%")
         .replace("_", "\\_")
      return "$escaped/%"
   }

   companion object {
      @Volatile
      private var INSTANCE: DbRecipeRepository? = null

      fun getInstance(application: Application): DbRecipeRepository {
         synchronized(this) {
            var instance = INSTANCE

            if (instance == null) {
               instance = DbRecipeRepository(application)
               INSTANCE = instance
            }

            return instance
         }
      }
   }

   fun getAllRecipePreviews(recipeDir: String) = mRecipeDao.getAllRecipePreviews(likePrefix(recipeDir))

   fun getRecipe(id: Long) = mRecipeDao.getById(id)

   fun getRecipeSync(id: Long): DbRecipe? = mRecipeDao.getByIdSync(id)

   fun filterCategory(
      sort: SortValue, category: String, recipeDir: String,
      recipeFilter: RecipeFilter? = null
   ): Flow<List<DbRecipePreview>> {
      var select = "SELECT DISTINCT $dbPreviewFields FROM recipes WHERE recipeCategory = '${category}' "
      if (recipeFilter != null && recipeFilter.type != RecipeFilter.QueryType.QUERY_INGREDIENTS) {
         select += " AND " + getWhereClause(recipeFilter)
      } else if (recipeFilter != null) {
         select =
            "SELECT DISTINCT $dbPreviewFields FROM recipes INNER JOIN ingredients ON recipes.id = ingredients" +
                  ".recipeId" +
                  " WHERE recipeCategory REGEXP '(^|,)\\s*${category} AND " + getWhereClause(recipeFilter)
      }
      select += " AND recipes.fs_filePath LIKE ? ESCAPE '\\' "
      select += " ORDER BY " + getOrderBy(sort)

      val args = if (recipeFilter != null) arrayOf(recipeFilter.query, likePrefix(recipeDir)) else arrayOf(likePrefix(recipeDir))
      val query = SimpleSQLiteQuery(select, args)
      return mRecipeDao.filterRecipes(query)
   }

   fun filterUncategorized(sort: SortValue, recipeDir: String, recipeFilter: RecipeFilter? = null): Flow<List<DbRecipePreview>> {
      var select = "SELECT DISTINCT $dbPreviewFields FROM recipes WHERE recipeCategory = ''"
      if (recipeFilter != null && recipeFilter.type != RecipeFilter.QueryType.QUERY_INGREDIENTS) {
         select += " AND " + getWhereClause(recipeFilter)
      } else if (recipeFilter != null) {
         select =
            "SELECT DISTINCT $dbPreviewFields FROM recipes" +
                  " INNER JOIN ingredients ON recipes.id = ingredients.recipeId" +
                  " WHERE recipeCategory = '' AND " + getWhereClause(recipeFilter)
      }

      select += " AND recipes.fs_filePath LIKE ? ESCAPE '\\' "
      select += " ORDER BY " + getOrderBy(sort)

      val args = if (recipeFilter != null) arrayOf(recipeFilter.query, likePrefix(recipeDir)) else arrayOf(likePrefix(recipeDir))

      val query = SimpleSQLiteQuery(select, args)
      return mRecipeDao.filterRecipes(query)
   }

   fun filterAll(sort: SortValue, recipeFilter: RecipeFilter, recipeDir: String): Flow<List<DbRecipePreview>> {
      var select = when (recipeFilter.type) {
         RecipeFilter.QueryType.QUERY_KEYWORD -> "SELECT DISTINCT $dbPreviewFields FROM recipes" +
               " INNER JOIN recipeXKeywords x ON x.recipeId = recipes.id" +
               " INNER JOIN keywords k ON k.id = x.keywordId" +
               " WHERE " + getWhereClause(recipeFilter)
         RecipeFilter.QueryType.QUERY_INGREDIENTS -> "SELECT DISTINCT $dbPreviewFields " +
               "FROM recipes INNER JOIN ingredients ON recipes.id = ingredients.recipeId " +
               "WHERE " + getWhereClause(recipeFilter)
         else -> "SELECT $dbPreviewFields FROM recipes WHERE " + getWhereClause(recipeFilter)
      }

      select += " AND recipes.fs_filePath LIKE ? ESCAPE '\\' "
      select += " ORDER BY " + getOrderBy(sort)

      val args = arrayOf(recipeFilter.query, likePrefix(recipeDir))

      val query = SimpleSQLiteQuery(select, args)
      return mRecipeDao.filterRecipes(query)
   }

   fun getAllFileInfos(): List<DbFilesystemRecipe> = mRecipeDao.getAllFileInfos()

   fun getKeywords() = mRecipeDao.getAllKeywords()

   fun sort(sort: SortValue, recipeDir: String): Flow<List<DbRecipePreview>> {
      val dirPrefix = likePrefix(recipeDir)
      return when (sort) {
         SortValue.NAME_A_Z -> mRecipeDao.sortByName(true, dirPrefix)
         SortValue.NAME_Z_A -> mRecipeDao.sortByName(false, dirPrefix)
         SortValue.DATE_ASC -> mRecipeDao.sortByDate(true, dirPrefix)
         SortValue.DATE_DESC -> mRecipeDao.sortByDate(false, dirPrefix)
         SortValue.TOTAL_TIME_ASC -> mRecipeDao.sortByTotalTime(true, dirPrefix)
         SortValue.TOTAL_TIME_DESC -> mRecipeDao.sortByTotalTime(false, dirPrefix)
      }
   }

   fun getCategories(recipeDir: String): Flow<List<String>> = mRecipeDao.getCategories(likePrefix(recipeDir))

   fun insertAll(recipes: List<DbRecipe>, recipeDir: String) {
      val dirPrefix = likePrefix(recipeDir)
      RecipeDatabase.databaseWriteExecutor.execute {
         if (recipes.isNotEmpty()) {
            mRecipeDao.deleteAllKeywordRelations()
            mRecipeDao.deleteAllKeywords()
         }
         recipes.forEach { recipe ->
            val r = mRecipeDao.findByNameInDir(recipe.recipeCore.name, dirPrefix)

            if (r == null) {
               val id = mRecipeDao.insert(recipe.recipeCore)
               setIdInLists(recipe, id)

               recipe.tool?.let { mRecipeDao.insertTools(it) }
               recipe.review?.let { mRecipeDao.insertReviews(it) }
               recipe.recipeInstructions?.let { mRecipeDao.insertInstructions(it) }
               recipe.recipeIngredient?.let { mRecipeDao.insertIngredients(it) }
               updateKeywords(recipe, id)
            } else {
               val id = r.recipeCore.id
               recipe.recipeCore.id = id
               setIdInLists(recipe, id)

               mRecipeDao.update(recipe.recipeCore)
               updateStar(recipe.recipeCore.id, r.recipeCore.starred)
               recipe.tool?.let { tools ->
                  r.tool?.let { mRecipeDao.deleteTools(it) }
                  mRecipeDao.insertTools(tools)
               }
               recipe.review?.let { reviews ->
                  r.review?.let { mRecipeDao.deleteReviews(it) }
                  mRecipeDao.insertReviews(reviews)
               }
               recipe.recipeInstructions?.let { instructions ->
                  r.recipeInstructions?.let { mRecipeDao.deleteInstructions(it) }
                  mRecipeDao.insertInstructions(instructions)
               }
               recipe.recipeIngredient?.let { ingredients ->
                  r.recipeIngredient?.let { mRecipeDao.deleteIngredients(it) }
                  mRecipeDao.insertIngredients(ingredients)
               }
               updateKeywords(recipe, id)
            }
         }
      }
   }

   fun updateStar(recipeId: Long, starred: Boolean) {
      RecipeDatabase.databaseWriteExecutor.execute {
         mRecipeDao.updateStar(DbRecipeStar(recipeId, starred))
      }
   }

   fun deleteRecipe(name: String) {
      RecipeDatabase.databaseWriteExecutor.execute {
         mRecipeDao.findByName(name)?.recipeCore?.let { mRecipeDao.delete(it) }
      }
   }

   /**
    * Removes duplicate recipe rows for the current account, keeping only
    * the most recently inserted row per duplicate name. Duplicates can
    * occur when a row inserted under an older insert/update matching rule
    * doesn't get recognized as "the same recipe" as a freshly re-synced
    * one, leaving two separate rows that both legitimately match the
    * current account's directory. Meant to be run on an explicit,
    * user-initiated refresh (swipe-to-refresh) rather than on every
    * background sync, since it's a maintenance pass rather than routine
    * sync work.
    */
   fun deleteDuplicates(recipeDir: String) {
      val dirPrefix = likePrefix(recipeDir)
      RecipeDatabase.databaseWriteExecutor.execute {
         for (duplicate in mRecipeDao.findDuplicates(dirPrefix)) {
            mRecipeDao.delete(duplicate.recipeCore)
         }
      }
   }

   private fun getWhereClause(recipeFilter: RecipeFilter): String {
      val upper = if (recipeFilter.ignoreCase) "UPPER(%s) " else "%s "

      var sql = when (recipeFilter.type) {
         RecipeFilter.QueryType.QUERY_NAME -> upper.format("name")
         RecipeFilter.QueryType.QUERY_KEYWORD -> upper.format("keyword")
         RecipeFilter.QueryType.QUERY_YIELD -> upper.format("recipeYield")
         RecipeFilter.QueryType.QUERY_INGREDIENTS -> upper.format("ingredient")
      }
      val operator = if (recipeFilter.exact) "= " else "LIKE '%' || "

      sql += operator
      sql += upper.format("?")

      if (operator != "= ")
         sql += "|| '%' "

      return sql
   }

   private fun getOrderBy(sort: SortValue): String {
      return "starred DESC, " + when (sort) {
         SortValue.NAME_A_Z -> "LOWER(name) asc"
         SortValue.NAME_Z_A -> "LOWER(name) desc"
         // datePublished is an optional schema.org field, only meaningfully
         // set for recipes imported from an external source that itself
         // declared a publish date -- most recipes leave it blank, so
         // sorting on it alone ties nearly everything together and the
         // visible order ends up dominated by incidental tie-breaking
         // (looking essentially alphabetical) rather than by date at all.
         // dateCreated is always stamped by the server regardless of how
         // a recipe was made, so falling back to it keeps the sort
         // meaningful for every recipe.
         SortValue.DATE_ASC -> "COALESCE(NULLIF(datePublished, ''), dateCreated) asc"
         SortValue.DATE_DESC -> "COALESCE(NULLIF(datePublished, ''), dateCreated) desc"
         SortValue.TOTAL_TIME_ASC -> "totalTime asc"
         SortValue.TOTAL_TIME_DESC -> "totalTime desc"
      }
   }

   /**
    * Sets the recipeId in every relation.
    */
   private fun setIdInLists(recipe: DbRecipe, id: Long) {
      recipe.tool?.let { t ->
         t.forEach { it.recipeId = id }
      }

      recipe.review?.let { reviews ->
         reviews.forEach { it.recipeId = id }
      }
      recipe.recipeInstructions?.let { ins ->
         ins.forEach { it.recipeId = id }
      }
      recipe.recipeIngredient?.let { ing ->
         ing.forEach { it.recipeId = id }
      }
   }

   private fun updateKeywords(recipe: DbRecipe, recipeId: Long) {
      recipe.keywords?.let { list ->
         if (list.isNotEmpty()) {
            mRecipeDao.insertKeywords(list)
            mRecipeDao.findKeywords(list.map { kw -> kw.keyword }).let {
               mRecipeDao.insertKeywordRefs(
                  it.map { kw -> DbRecipeKeywordRelation(recipeId = recipeId, keywordId = kw.id) })
            }
         }
      }
   }
}
