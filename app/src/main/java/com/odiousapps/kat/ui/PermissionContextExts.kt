/*
 * PermissionContextExts.kt
 *
 * Copyright 2023 by MicMun
 */
package com.odiousapps.kat.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.fondesa.kpermissions.PermissionStatus
import com.fondesa.kpermissions.request.PermissionRequest
import com.odiousapps.kat.R

/**
 * Extensions for context to ask for permissions.
 *
 * @author MicMun
 * @version 1.0, 25.03.23
 */

internal fun Context.showGrantedToast(permissions: List<PermissionStatus>) {
   val msg = getString(R.string.granted_permissions, permissions.toMessage<PermissionStatus.Granted>())
   Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

internal fun Context.showRationaleDialog(permissions: List<PermissionStatus>, request: PermissionRequest) {
   val msg = getString(R.string.rationale_permissions, permissions.toMessage<PermissionStatus.Denied.ShouldShowRationale>())

   AlertDialog.Builder(this)
      .setTitle(R.string.permissions_required)
      .setMessage(msg)
      .setPositiveButton(R.string.request_again) { _, _ ->
         // Send the request again.
         request.send()
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
}

internal fun Context.showPermanentlyDeniedDialog(msg: String) {
   AlertDialog.Builder(this)
      .setTitle(R.string.permissions_required)
      .setMessage(msg)
      .setPositiveButton(R.string.menu_settings) { _, _ ->
         // Open the app's settings.
         val intent = createAppSettingsIntent()
         startActivity(intent)
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
}

private fun Context.createAppSettingsIntent() = Intent().apply {
   action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
   data = Uri.fromParts("package", packageName, null)
}

private inline fun <reified T : PermissionStatus> List<PermissionStatus>.toMessage(): String = filterIsInstance<T>()
   .joinToString { it.permission }
