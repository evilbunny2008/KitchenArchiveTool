/*
 * RecipeImportCredentialStore.kt
 *
 * Copyright 2026 by MicMun
 */
package com.odiousapps.kat.nextcloudapi

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

/**
 * Stores the app password obtained via [NextcloudLoginFlow] for the
 * recipe-import feature, encrypted at rest, keyed by account name so
 * switching accounts doesn't reuse the wrong credential.
 *
 * This is a separate, dedicated app password -- not the account's real
 * login password, and not the token the Nextcloud SSO library itself
 * manages internally for this app's normal sync access. It only exists
 * to authenticate the server-side recipe-import bridge script, and can
 * be revoked independently at any time via Nextcloud's own Settings >
 * Security > Devices & sessions, without affecting anything else this
 * app does.
 */
object RecipeImportCredentialStore {

   private const val FILE_NAME = "recipe_import_credentials"

   data class Credentials(val server: String, val loginName: String, val appPassword: String)

   private fun prefs(context: Context): SharedPreferences {
      val masterKey = MasterKey.Builder(context)
         .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
         .build()

      return EncryptedSharedPreferences.create(
         context,
         FILE_NAME,
         masterKey,
         EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
         EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
      )
   }

   /** @param accountName the SSO account's own name (SingleSignOnAccount.name), not its display name */
   fun get(context: Context, accountName: String): Credentials? {
      val raw = prefs(context).getString(accountName, null) ?: return null
      return try {
         val json = JSONObject(raw)
         Credentials(
            server = json.getString("server"),
            loginName = json.getString("loginName"),
            appPassword = json.getString("appPassword"),
         )
      } catch (_: Exception) {
         null
      }
   }

   fun save(context: Context, accountName: String, credentials: Credentials) {
      val json = JSONObject().apply {
         put("server", credentials.server)
         put("loginName", credentials.loginName)
         put("appPassword", credentials.appPassword)
      }
      prefs(context).edit().putString(accountName, json.toString()).apply()
   }

   /**
    * Call this if the PHP bridge reports an auth failure (401/403) for a
    * stored credential -- it means the app password was revoked
    * server-side (e.g. from Nextcloud's own Devices & sessions page), so
    * the stale copy should be cleared rather than kept retrying with it
    * forever. The next import attempt will fall through to
    * RecipeImportLoginActivity to obtain a fresh one.
    */
   fun clear(context: Context, accountName: String) {
      prefs(context).edit().remove(accountName).apply()
   }
}
