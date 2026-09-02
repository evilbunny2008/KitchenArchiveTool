/*
 * CategoryFilter.kt
 *
 * Copyright 2020 by MicMun
 */
package de.micmun.android.nextcloudcookbook.data

/**
 * Filter for category.
 *
 * @author MicMun
 * @version 1.0, 08.08.20
 */
class CategoryFilter(val type: CategoryFilterOption, val name: String = "") {

   enum class CategoryFilterOption {
      ALL_CATEGORIES, UNCATEGORIZED, CATEGORY
   }

   override fun toString(): String {
      return "CategoryFilter(${type.name}, $name)"
   }

   override fun equals(other: Any?): Boolean {
      if (other == null)
         return false
      if (other !is CategoryFilter) {
         return false
      }
      if (other.type != this.type || other.name != this.name) {
         return false
      }
      return true
   }

   override fun hashCode(): Int {
      var result = type.hashCode()
      result = 31 * result + name.hashCode()
      return result
   }
}
