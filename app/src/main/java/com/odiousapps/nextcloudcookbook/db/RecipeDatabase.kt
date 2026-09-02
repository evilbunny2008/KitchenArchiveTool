/*
 * RecipeDatabase.kt
 *
 * Copyright 2021 by MicMun
 */
package com.odiousapps.nextcloudcookbook.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.odiousapps.nextcloudcookbook.db.model.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Database for recipes.
 *
 * @author MicMun
 * @version 1.0, 19.02.21
 */
@Database(
   entities = [DbRecipeCore::class, DbInstruction::class, DbIngredient::class,
      DbTool::class, DbKeyword::class, DbRecipeKeywordRelation::class, DbReview::class],
   version = 5, exportSchema = false
)
abstract class RecipeDatabase : RoomDatabase() {
   abstract fun recipeDataDao(): RecipeDataDao

   companion object {
      @Volatile
      private var INSTANCE: RecipeDatabase? = null
      private const val NUMBER_OF_THREADS = 4
      val databaseWriteExecutor: ExecutorService = Executors.newFixedThreadPool(NUMBER_OF_THREADS)

      fun getDatabase(context: Context): RecipeDatabase {
         synchronized(this) {
            var instance = INSTANCE

            if (instance == null) {
               instance = Room
                  .databaseBuilder(context.applicationContext, RecipeDatabase::class.java, "recipe-db")
                  // dropAllTables = true per Google's own recommendation: otherwise Room can
                  // leave obsolete data behind when table names/existence change between versions
                  .fallbackToDestructiveMigration(dropAllTables = true)
                  .build()
               INSTANCE = instance
            }
            return instance
         }
      }
   }
}
