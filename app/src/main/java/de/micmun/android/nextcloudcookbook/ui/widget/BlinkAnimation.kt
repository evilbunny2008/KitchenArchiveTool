package de.micmun.android.nextcloudcookbook.ui.widget

import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.Transformation

/**
 * Animation for blinking element.
 *
 * @author MicMun
 * @version 1.0, 28.07.21
 */
class BlinkAnimation : Animation() {

   init {
      interpolator = LinearInterpolator()
      duration = 1000
      repeatCount = 20
   }

   override fun applyTransformation(interpolatedTime: Float, t: Transformation) {
      t.alpha = when {
         interpolatedTime < 0.2f -> 1f
         interpolatedTime < 0.6f -> 0f
         else -> 1f
      }
   }
}
