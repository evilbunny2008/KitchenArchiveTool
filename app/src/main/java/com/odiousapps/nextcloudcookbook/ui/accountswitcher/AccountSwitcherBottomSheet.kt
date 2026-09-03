/*
 * AccountSwitcherBottomSheet.kt
 */
package com.odiousapps.nextcloudcookbook.ui.accountswitcher

import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.RelativeSizeSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.gson.GsonBuilder
import com.nextcloud.android.sso.AccountImporter
import com.nextcloud.android.sso.api.NextcloudAPI
import com.nextcloud.android.sso.helper.SingleAccountHelper
import com.odiousapps.nextcloudcookbook.R
import com.odiousapps.nextcloudcookbook.databinding.BottomSheetAccountSwitcherBinding
import com.odiousapps.nextcloudcookbook.databinding.ItemAccountSwitcherBinding
import com.odiousapps.nextcloudcookbook.nextcloudapi.Accounts
import com.odiousapps.nextcloudcookbook.nextcloudapi.UserInfoAPI
import com.odiousapps.nextcloudcookbook.services.sync.SyncService
import com.odiousapps.nextcloudcookbook.settings.PreferenceData
import com.odiousapps.nextcloudcookbook.util.Filesystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val NEXTCLOUD_ACCOUNT_TYPE = "nextcloud"

/**
 * Shows every Nextcloud account this app currently has access to, with the
 * active one marked by a checkmark. Unlike the system account chooser
 * (Accounts.openAccountChooser / AccountImporter.pickNewAccount, an
 * OS-owned dialog with no way for an app to indicate or pre-select a
 * specific entry), this is our own UI and can show that state directly.
 *
 * @author MicMun
 * @version 1.1
 */
class AccountSwitcherBottomSheet : BottomSheetDialogFragment() {

   private var _binding: BottomSheetAccountSwitcherBinding? = null
   private val binding get() = _binding!!

   override fun onCreateView(
      inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
   ): View {
      _binding = BottomSheetAccountSwitcherBinding.inflate(inflater, container, false)
      return binding.root
   }

   override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
      super.onViewCreated(view, savedInstanceState)

      val context = requireContext()
      binding.accountList.layoutManager = LinearLayoutManager(context)

      binding.addAccountRow.setOnClickListener {
         dismiss()
         Accounts(context).openAccountChooser(requireActivity())
      }

      binding.manageAccountsRow.setOnClickListener {
         dismiss()
         try {
            startActivity(Intent(Settings.ACTION_SYNC_SETTINGS))
         } catch (_: Exception) {
            // no accounts settings screen available on this device/OS variant
         }
      }

      // Fetching each account's display name requires a network call (see
      // UserInfoAPI) -- do this off the main thread, showing a loading
      // indicator in the meantime.
      viewLifecycleOwner.lifecycleScope.launch {
         val accounts = withContext(Dispatchers.IO) { loadAccounts(context) }

         _binding?.let { b ->
            b.loadingIndicator.visibility = View.GONE
            b.accountList.visibility = View.VISIBLE
            b.accountList.adapter = AccountSwitcherAdapter(accounts) { item ->
               if (!item.isCurrent) {
                  switchToAccount(context, item.accountName)
               }
               dismiss()
            }
         }
      }
   }

   override fun onDestroyView() {
      super.onDestroyView()
      _binding = null
   }

   /**
    * Switches the active account and points the recipe list at that
    * account's own folder, then kicks off a sync -- the same sequence
    * MainActivity/LoginActivity already perform when a NEW account is
    * added via the system chooser (AccountImporter.onActivityResult),
    * replicated here for switching between accounts already known to
    * this app. Each account has its own recipes/<accountName>/ folder
    * (see Sync.kt), so pointing recipeDir at it and letting
    * RecipeListFragment's existing reactive collector on that preference
    * pick up the change is what makes the recipe list show only this
    * account's recipes -- no direct coupling to RecipeListFragment needed.
    *
    * Syncing (not purging local recipes unless they're gone from the
    * server) is handled by Sync.kt's existing per-account logic; this
    * only needs to trigger it, same as the existing flows already do.
    */
   private fun switchToAccount(context: Context, accountName: String) {
      SingleAccountHelper.commitCurrentAccount(context, accountName)

      val prefs = PreferenceData.getInstance()
      viewLifecycleOwner.lifecycleScope.launch {
         withContext(Dispatchers.IO) {
            val externalDir = Filesystem(context).getInternalStoragePath()
            val accountRecipeDir = File(externalDir, "recipes/$accountName/")
            prefs.setRecipeDir(accountRecipeDir.absolutePath)
         }
         prefs.setSyncServiceEnabled()
         (activity as? AccountSwitcherHost)?.onAccountSwitched()
         context.startService(Intent(context, SyncService::class.java))
      }
   }

   /**
    * Loads every account this app has access to, along with each one's
    * server-side display name. Runs on a background thread -- must not be
    * called from the main thread, since it performs network requests.
    */
   private fun loadAccounts(context: Context): List<AccountSwitcherItem> {
      val currentAccountName = try {
         SingleAccountHelper.getCurrentSingleSignOnAccount(context).name
      } catch (_: Exception) {
         null
      }

      // Accounts this app has actually been granted access to via the SSO
      // handshake -- not every Nextcloud account on the device, only ones
      // previously authorized through Accounts.openAccountChooser().
      return AccountManager.get(context).getAccountsByType(NEXTCLOUD_ACCOUNT_TYPE)
         .mapNotNull { account ->
            try {
               val ssoAccount = AccountImporter.getSingleSignOnAccount(context, account.name)
               val hostname = Uri.parse(ssoAccount.url).host ?: ssoAccount.url

               var api: NextcloudAPI? = null
               val displayName = try {
                  api = NextcloudAPI(context, ssoAccount, GsonBuilder().create())
                  UserInfoAPI(api).getDisplayName()
               } catch (_: Exception) {
                  null
               } finally {
                  api?.close()
               } ?: ssoAccount.userId // fall back to the login username if the server call fails

               AccountSwitcherItem(
                  accountName = ssoAccount.name,
                  displayName = displayName,
                  subtitle = "${ssoAccount.userId}@$hostname",
                  avatarUrl = ssoAccount.url + "/index.php/avatar/" + Uri.encode(ssoAccount.userId) + "/64",
                  isCurrent = ssoAccount.name == currentAccountName
               )
            } catch (_: Exception) {
               // account known to AccountManager but not (or no longer)
               // accessible via SSO -- skip it rather than crash the list
               null
            }
         }
         // current account first, rest follow in their original order (stable sort)
         .sortedByDescending { it.isCurrent }
   }

   /**
    * Implemented by the hosting Activity so the bottom sheet can trigger a
    * UI refresh (avatar, tooltip) after switching accounts.
    */
   interface AccountSwitcherHost {
      fun onAccountSwitched()
   }

   private data class AccountSwitcherItem(
      val accountName: String,
      val displayName: String,
      /** "userId@hostname", shown in brackets and smaller print next to the display name */
      val subtitle: String,
      val avatarUrl: String,
      val isCurrent: Boolean
   )

   private class AccountSwitcherAdapter(
      private val items: List<AccountSwitcherItem>,
      private val onClick: (AccountSwitcherItem) -> Unit
   ) : RecyclerView.Adapter<AccountSwitcherAdapter.ViewHolder>() {

      override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
         val itemBinding = ItemAccountSwitcherBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
         )
         return ViewHolder(itemBinding)
      }

      override fun onBindViewHolder(holder: ViewHolder, position: Int) {
         holder.bind(items[position], onClick)
      }

      override fun getItemCount(): Int = items.size

      class ViewHolder(private val itemBinding: ItemAccountSwitcherBinding) :
         RecyclerView.ViewHolder(itemBinding.root) {

         fun bind(item: AccountSwitcherItem, onClick: (AccountSwitcherItem) -> Unit) {
            itemBinding.accountName.text = buildLabel(item.displayName, item.subtitle)
            itemBinding.accountCheck.visibility = if (item.isCurrent) View.VISIBLE else View.GONE
            Glide.with(itemBinding.root)
               .load(item.avatarUrl)
               .placeholder(R.drawable.ic_baseline_account_circle_24)
               .error(R.drawable.ic_baseline_account_circle_24)
               .apply(RequestOptions.circleCropTransform())
               .into(itemBinding.accountAvatar)
            itemBinding.root.setOnClickListener { onClick(item) }
         }

         /**
          * Builds "Display Name (userId@hostname)" as a single CharSequence,
          * with the bracketed part rendered smaller than the display name.
          */
         private fun buildLabel(displayName: String, subtitle: String): CharSequence {
            val bracketed = " ($subtitle)"
            return SpannableStringBuilder(displayName)
               .append(bracketed)
               .apply {
                  setSpan(
                     RelativeSizeSpan(0.8f),
                     displayName.length,
                     length,
                     Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                  )
               }
         }
      }
   }
}
