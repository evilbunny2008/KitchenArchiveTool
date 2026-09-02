/*
 * RecipeFilter.kt
 *
 * Copyright 2020 by MicMun
 */
package de.micmun.android.nextcloudcookbook.data

import android.os.Parcel
import android.os.Parcelable

/**
 * Filter class with the definition of a filter and the method to return the filtered list.
 *
 * @author MicMun
 * @version 1.1, 21.03.21
 */
class RecipeFilter(val type: QueryType, val query: String, val ignoreCase: Boolean = true,
                   val exact: Boolean = false) :
   Parcelable {
   constructor(parcel: Parcel) : this(
      QueryType.valueOf(parcel.readString()!!),
      parcel.readString()!!,
      parcel.readByte() != 0.toByte(),
      parcel.readByte() != 0.toByte())

   /**
    * Types of query.
    */
   enum class QueryType {
      QUERY_NAME,
      QUERY_KEYWORD,
      QUERY_INGREDIENTS,
      QUERY_YIELD
   }

   override fun writeToParcel(dest: Parcel, flags: Int) {
      dest.writeString(type.name)
      dest.writeString(query)
      dest.writeByte(if (ignoreCase) 1 else 0)
      dest.writeByte(if (exact) 1 else 0)
   }

   override fun describeContents(): Int {
      return 0
   }

   companion object CREATOR : Parcelable.Creator<RecipeFilter> {
      override fun createFromParcel(parcel: Parcel): RecipeFilter {
         return RecipeFilter(parcel)
      }

      override fun newArray(size: Int): Array<RecipeFilter?> {
         return arrayOfNulls(size)
      }
   }

   override fun toString(): String {
      return type.name+" "+query+" " + ignoreCase.toString() + " " +exact.toString()
   }
}