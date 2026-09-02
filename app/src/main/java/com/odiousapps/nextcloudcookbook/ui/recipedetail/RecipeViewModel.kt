/*
 * RecipeViewModel.kt
 *
 * Copyright 2020 by MicMun
 */
package com.odiousapps.nextcloudcookbook.ui.recipedetail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.odiousapps.nextcloudcookbook.db.DbRecipeRepository
import com.odiousapps.nextcloudcookbook.db.model.DbRecipe
import com.odiousapps.nextcloudcookbook.util.CookTimer

/**
 * ViewModel for one Recipe.
 *
 * @author MicMun
 * @version 1.2, 05.03.23
 */
class RecipeViewModel(id: Long, application: Application) :
   AndroidViewModel(application) {
   private val repository = DbRecipeRepository.getInstance(application)
   val recipe: LiveData<DbRecipe?> = repository.getRecipe(id)

   // cooktimer
   internal var total: Long? = null // total milliseconds

    // current milliseconds remaining time
    internal val currentMillis: LiveData<Long>
        field = MutableLiveData(-1L)

    // state of the timer
    val state: LiveData<CooktimeState>
        field = MutableLiveData(CooktimeState.NOT_STARTED)

    private var cooktimer: CookTimer? = null

   /**
    * Starts the timer.
    */
   internal fun startTimer() {
      val timerMillis = when {
         currentMillis.value!! == -1L -> total!!
         else -> currentMillis.value!!
      }
      cooktimer = CookTimer(timerMillis, object : CookTimer.CookTimeListener {
         override fun refreshOnTick(remains: Long) {
            currentMillis.postValue(remains)
            state.postValue(CooktimeState.RUNNING)
         }

         override fun refreshOnFinish() {
            state.postValue(CooktimeState.FINISHED)
         }
      })
      cooktimer?.start()
   }

   /**
    * Stops the timer.
    */
   internal fun stopTimer() {
      cooktimer?.cancel()
      cooktimer = null
      state.value = CooktimeState.PAUSED
   }

   /**
    * Sets timer back to zero.
    */
   internal fun resetTimer() {
      currentMillis.value = -1L
      state.value = CooktimeState.NOT_STARTED
   }

   /**
    * Sets the current milliseconds.
    *
    * @param millis current milliseconds.
    */
   internal fun setCurrentMillis(millis: Long) {
      currentMillis.value = millis
   }

   override fun onCleared() {
      cooktimer?.cancel()
      cooktimer = null
   }

   /**
    * Enumeration for state of Timer.
    *
    * @author MicMun
    * @version 1.0, 28.07.21
    */
   enum class CooktimeState {
      RUNNING,
      PAUSED,
      FINISHED,
      NOT_STARTED
   }
}
