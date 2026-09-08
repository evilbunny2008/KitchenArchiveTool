package com.odiousapps.kat.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.fondesa.kpermissions.allGranted
import com.fondesa.kpermissions.anyPermanentlyDenied
import com.fondesa.kpermissions.anyShouldShowRationale
import com.fondesa.kpermissions.extension.permissionsBuilder
import com.fondesa.kpermissions.request.PermissionRequest
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.nextcloud.android.sso.AccountImporter
import com.nextcloud.android.sso.exceptions.AccountImportCancelledException
import com.nextcloud.android.sso.exceptions.NextcloudFilesAppAccountNotFoundException
import com.nextcloud.android.sso.exceptions.NoCurrentAccountSelectedException
import com.nextcloud.android.sso.helper.SingleAccountHelper
import com.nextcloud.android.sso.model.SingleSignOnAccount
import com.nextcloud.android.sso.ui.UiExceptionManager
import com.odiousapps.kat.R
import com.odiousapps.kat.nextcloudapi.Accounts
import com.odiousapps.kat.services.sync.SyncScheduler
import com.odiousapps.kat.settings.PreferenceData
import com.odiousapps.kat.util.Filesystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Login/first-run screen.
 *
 * The post-login initial sync goes through [SyncScheduler] (WorkManager),
 * the same mechanism used everywhere else in the app -- not a raw
 * background thread owned directly by this Activity. That distinction
 * matters specifically here: unlike most other screens, it's common to
 * rotate the device (or otherwise trigger a configuration change) while
 * sitting on this exact screen waiting for the first sync to finish. A
 * raw thread spawned from onActivityResult doesn't survive that -- the
 * Activity instance that owns it gets destroyed and recreated, the new
 * instance has no idea a sync is already running, and the OLD thread
 * keeps writing to local storage in the background regardless, with no
 * visible progress and no correctly-delivered completion callback. Worse,
 * if the person then proceeds through the freshly-recreated screen too
 * (since it just shows the login/skip buttons again, as if nothing had
 * happened), a second sync can end up running concurrently with the
 * first, both writing to the same local recipe files at once -- which is
 * consistent with reports of an incomplete import that a later
 * pull-to-refresh didn't fix either (a subsequent sync's "already
 * up to date" check can be fooled by state left half-written by the
 * race).
 *
 * WorkManager avoids this on both fronts: the work itself isn't tied to
 * this Activity's lifetime at all, and SyncScheduler.syncNow()'s
 * ExistingWorkPolicy.KEEP means a second trigger while one is already
 * running is simply ignored rather than starting a competing sync.
 * [isSyncing] is saved/restored across recreation (see
 * onSaveInstanceState) purely so the UI itself doesn't flash back to the
 * login button, and [SyncScheduler.observeManualSyncState] is used
 * instead of a one-shot broadcast specifically because a fresh
 * observer needs to correctly pick up "already finished" too, not just
 * future transitions -- otherwise a completion that happened to land
 * between one Activity instance being destroyed and the next one's
 * observer registering would simply never be seen.
 */
class LoginActivity : AppCompatActivity() {

   companion object {
      private const val SKIP_PREFERENCE = "cookbook_skip_login_preference_key"
      private const val SKIP_PREFERENCE_FILE = "cookbook_login_preference"
      private const val SKIP_PREFERENCE_DEFAULT = false
      private const val STATE_IS_SYNCING = "is_syncing"
   }

   private lateinit var request: PermissionRequest

   /** True from the moment this login flow itself triggers a sync until that sync finishes. */
   private var isSyncing = false

   override fun onCreate(savedInstanceState: Bundle?) {
      super.onCreate(savedInstanceState)
      setContentView(R.layout.activity_login)
      isSyncing = savedInstanceState?.getBoolean(STATE_IS_SYNCING) ?: false

      val settings = getSharedPreferences(SKIP_PREFERENCE_FILE, MODE_PRIVATE)
      val skipLogin = settings.getBoolean(SKIP_PREFERENCE, SKIP_PREFERENCE_DEFAULT)
      if (skipLogin) {
         if (!PreferenceData.getInstance().isSyncServiceEnabled()) {
            val allowStorageAccess: Int = checkCallingOrSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (allowStorageAccess == PackageManager.PERMISSION_GRANTED) {
               val main = Intent(this@LoginActivity, MainActivity::class.java)
               startActivity(main)
               finish()
            } else {
               Snackbar.make(
                  findViewById(R.id.buttonLogin),
                  getString(R.string.storage_permissions_not_granted),
                  Snackbar.LENGTH_LONG
               ).show()
            }
         } else {
            val main = Intent(this@LoginActivity, MainActivity::class.java)
            startActivity(main)
            finish()
         }
      }

      val login: Button = findViewById(R.id.buttonLogin)
      val skip: Button = findViewById(R.id.buttonSkip)

      // View.clipToOutline (the XML attribute, API 31+) vs
      // View.setClipToOutline() (the method, API 21+): the same
      // property, but the XML attribute wasn't added to the platform
      // until API 31, well above this app's minSdk of 29. Setting it
      // here instead works across this app's whole supported range from
      // a single layout file, no -v31 layout variant needed.
      findViewById<ImageView>(R.id.image).clipToOutline = true

      login.setOnClickListener {
         Accounts(applicationContext).openAccountChooser(this)
      }

      // request for storage permission
      request = permissionsBuilder(
         Manifest.permission.READ_EXTERNAL_STORAGE,
         Manifest.permission.WRITE_EXTERNAL_STORAGE
      ).build()

      skip.setOnClickListener {
         skipAndOpenApp()
      }

      if (isSyncing) {
         showSyncingUi()
      }

      SyncScheduler.observeManualSyncState(this).observe(this) { workInfos ->
         // Only react once this login flow has actually triggered a sync
         // itself -- SyncScheduler's unique work name is shared with every
         // other sync trigger in the app (periodic background sync,
         // pull-to-refresh), so without this guard, simply opening this
         // screen could pick up a stale "already finished" result from a
         // completely unrelated sync that ran ages ago in a past session.
         if (!isSyncing) return@observe

         if (workInfos.any { !it.state.isFinished }) {
            showSyncingUi()
         } else if (workInfos.isNotEmpty()) {
            // Finished, success or failure either way -- don't trap the
            // person on this screen forever over a failed initial sync;
            // they can always pull-to-refresh once inside the app.
            skipAndOpenApp()
         }
      }
   }

   override fun onSaveInstanceState(outState: Bundle) {
      super.onSaveInstanceState(outState)
      outState.putBoolean(STATE_IS_SYNCING, isSyncing)
   }

   @Deprecated("Deprecated in Java")
   override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
      @Suppress("DEPRECATION")
      super.onActivityResult(requestCode, resultCode, data)
      try {
         AccountImporter.onActivityResult(
            requestCode, resultCode, data, this
         ) { account ->
            val context = applicationContext

            // As this library supports multiple accounts we created some helper methods if you only want to use one.
            // The following line stores the selected account as the "default" account which can be queried by using
            // the SingleAccountHelper.getCurrentSingleSignOnAccount(context) method
            SingleAccountHelper.commitCurrentAccount(context, account.name)

            // Get the "default" account
            var ssoAccount: SingleSignOnAccount? = null
            try {
               ssoAccount = SingleAccountHelper.getCurrentSingleSignOnAccount(context)
            } catch (e: NextcloudFilesAppAccountNotFoundException) {
               UiExceptionManager.showDialogForException(context, e)
            } catch (e: NoCurrentAccountSelectedException) {
               UiExceptionManager.showDialogForException(context, e)
            }
            SingleAccountHelper.commitCurrentAccount(context, ssoAccount!!.name)
            val username = ssoAccount.name

            val externalDir = Filesystem(context).getInternalStoragePath()
            val file = File(externalDir, "recipes/$username/")
            val prefs = PreferenceData.getInstance()
            runBlocking {
               withContext(Dispatchers.IO) {
                  prefs.setRecipeDir(file.absolutePath)
               }
            }

            PreferenceData.getInstance().setSyncServiceEnabled()

            isSyncing = true
            showSyncingUi()
            SyncScheduler.syncNow(context)
            // The WorkManager observer registered in onCreate takes it
            // from here -- it'll show the syncing UI for as long as the
            // work is running (surviving this Activity being recreated,
            // if that happens) and move on to MainActivity once it's done.
         }
      } catch (_: AccountImportCancelledException) { }
   }

   private fun showSyncingUi() {
      findViewById<CircularProgressIndicator>(R.id.progress_circular).apply {
         isIndeterminate = true
         visibility = View.VISIBLE
      }
      findViewById<TextView>(R.id.progress_text).text = getString(R.string.syncing_recipes)
      findViewById<Button>(R.id.buttonLogin).visibility = View.GONE
      findViewById<Button>(R.id.buttonSkip).visibility = View.GONE
   }

   private fun skipAndOpenApp() {
      val settings = getSharedPreferences(SKIP_PREFERENCE_FILE, MODE_PRIVATE)
       settings.edit {
           putBoolean(SKIP_PREFERENCE, true)
       }

      // permission for storage
      lifecycleScope.launch {
         lifecycle.repeatOnLifecycle(Lifecycle.State.CREATED) {
            storagePermissions()
         }
      }
   }

   /**
    * Handles the default storage permissions.
    */
   private fun storagePermissions() {
      val permissions = request.checkStatus()

      if (permissions.allGranted()) {
         startMain()
      } else {
         request.addListener { result ->
            when {
               result.anyPermanentlyDenied() -> startMain() //showPermanentlyDeniedDialog(result)
               result.anyShouldShowRationale() -> showRationaleDialog(result, request)
               result.allGranted() -> {
                  startMain()
               }
            }
         }
         request.send()
      }
   }

   private fun startMain() {
      val main = Intent(this, MainActivity::class.java)
      startActivity(main)
      finish()
   }
}
