package com.odiousapps.nextcloudcookbook.ui

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.fondesa.kpermissions.*
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
import com.odiousapps.nextcloudcookbook.R
import com.odiousapps.nextcloudcookbook.nextcloudapi.Accounts
import com.odiousapps.nextcloudcookbook.nextcloudapi.Sync
import com.odiousapps.nextcloudcookbook.services.sync.SyncProgressIndicatorInterface
import com.odiousapps.nextcloudcookbook.settings.PreferenceData
import com.odiousapps.nextcloudcookbook.util.Filesystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors


class LoginActivity : AppCompatActivity(), SyncProgressIndicatorInterface {

   companion object {
      private const val TAG = "LoginActivity"
      private const val SKIP_PREFERENCE = "cookbook_skip_login_preference_key"
      private const val SKIP_PREFERENCE_FILE = "cookbook_login_preference"
      private const val SKIP_PREFERENCE_DEFAULT = false
      //private const val REQUEST_STORAGE_ID = 4242421
   }

   private lateinit var request: PermissionRequest

   override fun onCreate(savedInstanceState: Bundle?) {
      super.onCreate(savedInstanceState)
      setContentView(R.layout.activity_login)

      val settings = getSharedPreferences(SKIP_PREFERENCE_FILE, MODE_PRIVATE)
      val skipLogin = settings.getBoolean(SKIP_PREFERENCE, SKIP_PREFERENCE_DEFAULT)
      if (skipLogin) {
         if (!PreferenceData.getInstance().isSyncServiceEnabled()) {
            val allowStorageAccess: Int = checkCallingOrSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (allowStorageAccess == PackageManager.PERMISSION_GRANTED) {
               val main = Intent(this@LoginActivity, MainActivity::class.java)
               startActivity(main)
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
         }
      }

      val login: Button = findViewById(R.id.buttonLogin)
      val skip: Button = findViewById(R.id.buttonSkip)

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
            SingleAccountHelper.setCurrentAccount(context, account.name)

            // Get the "default" account
            var ssoAccount: SingleSignOnAccount? = null
            try {
               ssoAccount = SingleAccountHelper.getCurrentSingleSignOnAccount(context)
            } catch (e: NextcloudFilesAppAccountNotFoundException) {
               UiExceptionManager.showDialogForException(context, e)
            } catch (e: NoCurrentAccountSelectedException) {
               UiExceptionManager.showDialogForException(context, e)
            }
            SingleAccountHelper.setCurrentAccount(context, ssoAccount!!.name)
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

            val mContext = this



            Executors.newSingleThreadExecutor().submit {
               try {
                  val sync = Sync(mContext)
                  sync.registerUpdateCallback(this)
                  sync.synchronizeRecipes()
                  sync.closeAPI()
                  skipAndOpenApp()
                  val main = Intent(this@LoginActivity, MainActivity::class.java)
                  startActivity(main)
               } catch (e: Exception) {
                  Log.e(TAG, "Error Syncing: " + e.message)
               }
            }
         }
      } catch (_: AccountImportCancelledException) { }
   }

   private fun skipAndOpenApp() {
      val settings = getSharedPreferences(SKIP_PREFERENCE_FILE, MODE_PRIVATE)
      val editor: SharedPreferences.Editor = settings.edit()
      editor.putBoolean(SKIP_PREFERENCE, true)
      editor.apply()

      // permission for storage
      lifecycleScope.launchWhenCreated {
         storagePermissions()
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
   }

   override fun updateProgress(item: Int, overall: Int, title: String) {
      this@LoginActivity.runOnUiThread {
         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val progressBar = (this.findViewById(R.id.progress_circular) as CircularProgressIndicator)
            progressBar.isIndeterminate = false
            progressBar.secondaryProgress = (item*100)/overall
            progressBar.visibility = View.VISIBLE
         }
         (this.findViewById(R.id.progress_text) as TextView).text = "$item/$overall - $title"
         (this.findViewById(R.id.buttonLogin) as Button).visibility = View.GONE
         (this.findViewById(R.id.buttonSkip) as Button).visibility = View.GONE

      }
   }
}
