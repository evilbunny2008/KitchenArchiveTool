/*
 * Pref.kt
 *
 * Copyright 2020 by MicMun
 */
package de.micmun.android.nextcloudcookbook.settings

/**
 * Constants for pref name.
 *
 * @author MicMun
 * @version 1.5, 27.11.21
 */
class Pref {
   companion object {
      const val IS_INIT = "is_initialized"
      const val RECIPE_DIR = "recipe_directory"
      const val THEME = "theme_setting"
      const val SCREEN_KEEPALIVE = "keep_screen_alive"
      const val SORT = "recipe_list_sorting"
      const val STORAGE_ACCESS = "storage_access"
      const val SYNC_SERVICE = "enableSyncService"
      const val SYNC_WIFI_ONLY = "sync_wifi_only"
   }
}
