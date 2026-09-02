/*
 * AccountSwitcherBottomSheet.kt
 */
package com.odiousapps.nextcloudcookbook.ui.accountswitcher

import android.accounts.AccountManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.nextcloud.android.sso.AccountImporter
import com.nextcloud.android.sso.helper.SingleAccountHelper
import com.odiousapps.nextcloudcookbook.R
import com.odiousapps.nextcloudcookbook.databinding.BottomSheetAccountSwitcherBinding
import com.odiousapps.nextcloudcookbook.databinding.ItemAccountSwitcherBinding
import com.odiousapps.nextcloudcookbook.nextcloudapi.Accounts

private const val NEXTCLOUD_ACCOUNT_TYPE = "nextcloud"

/**
 * Shows every Nextcloud account this app currently has access to, with the
 * active one marked by a checkmark. Unlike the system account chooser
 * (Accounts.openAccountChooser / AccountImporter.pickNewAccount, an
 * OS-owned dialog with no way for an app to indicate or pre-select a
 * specific entry), this is our own UI and can show that state directly.
 *
 * @author MicMun
 * @version 1.0
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
      val currentAccountName = try {
         SingleAccountHelper.getCurrentSingleSignOnAccount(context).name
      } catch (_: Exception) {
         null
      }

      // Accounts this app has actually been granted access to via the SSO
      // handshake -- not every Nextcloud account on the device, only ones
      // previously authorized through Accounts.openAccountChooser().
      val accounts = AccountManager.get(context).getAccountsByType(NEXTCLOUD_ACCOUNT_TYPE)
         .mapNotNull { account ->
            try {
               val ssoAccount = AccountImporter.getSingleSignOnAccount(context, account.name)
               AccountSwitcherItem(
                  accountName = ssoAccount.name,
                  displayName = ssoAccount.userId,
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

      binding.accountList.layoutManager = LinearLayoutManager(context)
      binding.accountList.adapter = AccountSwitcherAdapter(accounts) { item ->
         if (!item.isCurrent) {
            SingleAccountHelper.commitCurrentAccount(context, item.accountName)
            (activity as? AccountSwitcherHost)?.onAccountSwitched()
         }
         dismiss()
      }

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
   }

   override fun onDestroyView() {
      super.onDestroyView()
      _binding = null
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
            itemBinding.accountName.text = item.displayName
            itemBinding.accountCheck.visibility = if (item.isCurrent) View.VISIBLE else View.GONE
            Glide.with(itemBinding.root)
               .load(item.avatarUrl)
               .placeholder(R.drawable.ic_baseline_account_circle_24)
               .error(R.drawable.ic_baseline_account_circle_24)
               .apply(RequestOptions.circleCropTransform())
               .into(itemBinding.accountAvatar)
            itemBinding.root.setOnClickListener { onClick(item) }
         }
      }
   }
}
