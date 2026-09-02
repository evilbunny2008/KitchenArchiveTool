/*
 * CooktimerServiceViewModel.kt
 *
 * Copyright 2021 by MicMun
 */
package com.odiousapps.nextcloudcookbook.services

import android.app.Application
import androidx.lifecycle.*
import com.odiousapps.nextcloudcookbook.util.CookTimer

/**
 * ViewModel for CooktimerService.
 *
 * @author MicMun
 * @version 1.0, 30.07.21
 */
class CooktimerServiceViewModel(application: Application) : AndroidViewModel(application) {
    val cooktimer: LiveData<CookTimer>
        field = MutableLiveData<CookTimer>()

    val remains: LiveData<Long>
        field = MutableLiveData<Long>()

    /**
    * Sets the timer for a count of milliseconds.
    *
    * @param timeInMillis count of milliseconds.
    */
   internal fun setTimer(timeInMillis: Long) {
      cooktimer.value = CookTimer(timeInMillis, object : CookTimer.CookTimeListener {
         override fun refreshOnTick(remains: Long) {
            this@CooktimerServiceViewModel.remains.postValue(remains)
         }

         override fun refreshOnFinish() {
            remains.value = 0
         }
      })
   }

   /**
    * Starts the timer.
    *
    * @param timer CookTimer.
    */
   internal fun startTimer(timer: CookTimer) {
      timer.start()
   }

   /**
    * Stops the timer.
    */
   internal fun stopTimer() {
      cooktimer.value?.cancel()
   }
}

class CooktimerServiceViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
   @Suppress("UNCHECKED_CAST")
   override fun <T : ViewModel> create(modelClass: Class<T>): T {
      if (modelClass.isAssignableFrom(CooktimerServiceViewModel::class.java)) {
         return CooktimerServiceViewModel(application) as T
      }
      throw IllegalArgumentException("Unknown ViewModel class")
   }
}
