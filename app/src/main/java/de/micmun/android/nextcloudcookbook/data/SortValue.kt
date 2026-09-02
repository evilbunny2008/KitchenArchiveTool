/*
 * SortValue.kt
 *
 * Copyright 2020 by MicMun
 */
package de.micmun.android.nextcloudcookbook.data

/**
 * Enumeration of sort values.
 *
 * @author MicMun
 * @version 1.1, 08.08.20
 */
enum class SortValue(val sort: Int) {
   NAME_A_Z(0),
   NAME_Z_A(1),
   DATE_ASC(2),
   DATE_DESC(3),
   TOTAL_TIME_ASC(4),
   TOTAL_TIME_DESC(5);

   companion object {
      private val values = values()
      fun getByValue(sort: Int) = values.firstOrNull { it.sort == sort } ?: NAME_A_Z
   }
}
