/*
 * IntListPreference.kt
 *
 * Copyright 2020 by MicMun
 */
package de.micmun.android.nextcloudcookbook.ui.preferences

import android.content.Context
import android.util.AttributeSet
import androidx.preference.ListPreference

/**
 * ListPreference for Int values.
 *
 * @author MicMun
 * @version 1.1, 07.04.21
 */
class IntListPreference : ListPreference {
   constructor(context: Context) : super(context)
   constructor(context: Context, attr: AttributeSet) : super(context, attr)
   constructor(context: Context, attr: AttributeSet, defStyleAttr: Int) : super(context, attr, defStyleAttr)
   constructor(context: Context, attr: AttributeSet, defStyleAttr: Int, defStyleRes: Int) : super(context,
                                                                                                  attr,
                                                                                                  defStyleAttr,
                                                                                                  defStyleRes)

   override fun persistString(value: String?): Boolean {
      val intValue = value!!.toInt()
      return persistInt(intValue)
   }

   override fun getPersistedString(defaultReturnValue: String?): String {

      val intValue: Int = if (defaultReturnValue != null) {
         val intDefaultReturnValue = defaultReturnValue.toInt()
         getPersistedInt(intDefaultReturnValue)
      } else {
         if (getPersistedInt(0) == getPersistedInt(1)) {
            getPersistedInt(0)
         } else {
            throw IllegalArgumentException("Cannot get an int without a default return value")
         }
      }

      return intValue.toString()
   }
}
