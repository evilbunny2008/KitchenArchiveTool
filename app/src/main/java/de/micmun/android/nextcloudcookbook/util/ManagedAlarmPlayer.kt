package de.micmun.android.nextcloudcookbook.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer

/**
 * Alarm player for cooking timer.
 *
 * @author MicMun
 * @version 1.0, 28.07.21
 */
class ManagedAlarmPlayer constructor(val context: Context) {

   private var alarmPlayer: MediaPlayer? = null

   var isPlaying = false
      private set

   /**
    * Start playing an alarm sound.
    *
    * @param resId resource id for sound.
    */
   fun play(resId: Int) {
      if (!isPlaying) {
         isPlaying = true
         alarmPlayer = MediaPlayer.create(context, resId,
                                          AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
                                          (context.getSystemService(
                                             Context.AUDIO_SERVICE) as AudioManager).generateAudioSessionId())
         alarmPlayer?.start()
      }
   }

   /**
    * Stop playing the sound.
    */
   fun stop() {
      if (isPlaying) {
         alarmPlayer?.stop()
         alarmPlayer?.release()
         alarmPlayer = null
         isPlaying = false
      }
   }
}
