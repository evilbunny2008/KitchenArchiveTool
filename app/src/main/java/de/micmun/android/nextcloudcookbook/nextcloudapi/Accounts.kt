package de.micmun.android.nextcloudcookbook.nextcloudapi

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.gson.GsonBuilder
import com.nextcloud.android.sso.AccountImporter
import com.nextcloud.android.sso.api.NextcloudAPI
import com.nextcloud.android.sso.exceptions.AndroidGetAccountsPermissionNotGranted
import com.nextcloud.android.sso.exceptions.NextcloudFilesAppAccountNotFoundException
import com.nextcloud.android.sso.exceptions.NextcloudFilesAppNotInstalledException
import com.nextcloud.android.sso.exceptions.NoCurrentAccountSelectedException
import com.nextcloud.android.sso.helper.SingleAccountHelper
import com.nextcloud.android.sso.model.SingleSignOnAccount
import com.nextcloud.android.sso.ui.UiExceptionManager
import de.micmun.android.nextcloudcookbook.services.sync.SyncService

class Accounts(private val mContext: Context) {

   fun openAccountChooser(activity: Activity) {
      try {
         AccountImporter.pickNewAccount(activity)
      } catch (e: NextcloudFilesAppNotInstalledException) {
         UiExceptionManager.showDialogForException(activity, e)
      } catch (e: AndroidGetAccountsPermissionNotGranted) {
         UiExceptionManager.showDialogForException(activity, e)
      }
   }

   fun resetAccount() {
      SingleAccountHelper.setCurrentAccount(mContext, "")
   }

   fun getCurrentAccount(): SingleSignOnAccount? {
      try {
         return SingleAccountHelper.getCurrentSingleSignOnAccount(mContext)
      } catch (noFiles: NextcloudFilesAppAccountNotFoundException) {
         noFiles.printStackTrace()
      } catch (noCurrentAccount: NoCurrentAccountSelectedException) {
         noCurrentAccount.printStackTrace()
      }
      return null
   }

   fun getApiToAccount(): NextcloudAPI? {
      val sso = getCurrentAccount()
      if (sso != null) {
         return NextcloudAPI(mContext, getCurrentAccount()!!, GsonBuilder().create())
      }
      return null
   }
}