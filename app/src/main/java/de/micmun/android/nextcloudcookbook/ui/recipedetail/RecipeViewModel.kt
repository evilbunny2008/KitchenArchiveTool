/*
 * RecipeViewModel.kt
 *
 * Copyright 2020 by MicMun
 */
package de.micmun.android.nextcloudcookbook.ui.recipedetail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import de.micmun.android.nextcloudcookbook.db.DbRecipeRepository
import de.micmun.android.nextcloudcookbook.db.model.DbRecipe
import de.micmun.android.nextcloudcookbook.util.CookTimer

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
   private val _currentMillis = MutableLiveData(-1L)
   internal val currentMillis: LiveData<Long>
      get() = _currentMillis

   // state of the timer
   private val _state = MutableLiveData(CooktimeState.NOT_STARTED)
   val state: LiveData<CooktimeState>
      get() = _state

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
            _currentMillis.postValue(remains)
            _state.postValue(CooktimeState.RUNNING)
         }

         override fun refreshOnFinish() {
            _state.postValue(CooktimeState.FINISHED)
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
      _state.value = CooktimeState.PAUSED
   }

   /**
    * Sets timer back to zero.
    */
   internal fun resetTimer() {
      _currentMillis.value = -1L
      _state.value = CooktimeState.NOT_STARTED
   }

   /**
    * Sets the current milliseconds.
    *
    * @param millis current milliseconds.
    */
   internal fun setCurrentMillis(millis: Long) {
      _currentMillis.value = millis
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
