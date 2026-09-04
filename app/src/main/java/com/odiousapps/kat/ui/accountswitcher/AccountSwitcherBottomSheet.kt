/*
 * AccountSwitcherBottomSheet.kt
 */
package com.odiousapps.kat.ui.accountswitcher

import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.odiousapps.kat.R
import com.odiousapps.kat.databinding.BottomSheetAccountSwitcherBinding
import com.odiousapps.kat.databinding.ItemAccountSwitcherBinding
import com.odiousapps.kat.nextcloudapi.Accounts
import com.odiousapps.kat.nextcloudapi.AvatarCache
import com.odiousapps.kat.nextcloudapi.AvatarFetcher
import com.odiousapps.kat.nextcloudapi.UserInfoAPI
import com.odiousapps.kat.services.sync.SyncScheduler
import com.odiousapps.kat.settings.PreferenceData
import com.odiousapps.kat.util.Filesystem
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val NEXTCLOUD_ACCOUNT_TYPE = "nextcloud"

/** ByteArray's own == is reference equality, not content -- this is null-safe content comparison. */
private fun bytesEqual(a: ByteArray?, b: ByteArray?): Boolean {
   if (a == null || b == null) return a == null && b == null
   return a.contentEquals(b)
}

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

      // The initial render is local-only (AccountManager + SSO account
      // info, no network) so it appears essentially instantly regardless
      // of how many accounts are signed in -- matching how the Nextcloud
      // Files app's own account switcher shows up immediately. Each row's
      // real display name and avatar are fetched afterward, in the
      // background, concurrently across accounts rather than one at a
      // time -- see enrichAccountsInBackground's doc comment for why this
      // replaced the previous sequential-per-account approach.
      viewLifecycleOwner.lifecycleScope.launch {
         val accounts = withContext(Dispatchers.IO) { loadAccountsFast(context) }

         val adapter = AccountSwitcherAdapter(accounts) { item ->
            if (!item.isCurrent) {
               switchToAccount(context, item.accountName)
            }
            dismiss()
         }

         _binding?.let { b ->
            b.loadingIndicator.visibility = View.GONE
            b.accountList.visibility = View.VISIBLE
            b.accountList.adapter = adapter
         }

         enrichAccountsInBackground(context, accounts) { updated ->
            adapter.updateItem(updated)
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
      val appContext = context.applicationContext
      // Captured on the main thread, before hopping to a background
      // thread below -- Fragment.getActivity() isn't safe to call off
      // the main thread.
      val hostActivity = activity as? AccountSwitcherHost
      val mainHandler = Handler(Looper.getMainLooper())

      // Deliberately NOT using viewLifecycleOwner.lifecycleScope here: the
      // row's click handler calls dismiss() immediately after this
      // function returns, which tears this fragment's view down almost
      // right away and cancels that scope -- a coroutine launched on it
      // was very likely being cancelled before onAccountSwitched()/
      // syncNow() ever ran. That matches exactly what was reported: the
      // recipe list did update (fs_filePath's write below tends to land
      // before cancellation catches up with it, since it starts first),
      // but the account-switcher avatar and the immediate post-switch
      // sync -- the last steps in this sequence -- reliably lost the race
      // and never fired, leaving the previous account's avatar showing
      // until the next full app restart (when MainActivity.onCreate's own
      // independent updateProfilePicture() call runs instead). A plain
      // background executor survives the bottom sheet closing, same
      // pattern CopyToAccountBottomSheet uses for its own post-dismiss
      // background work.
      Executors.newSingleThreadExecutor().submit {
         val externalDir = Filesystem(appContext).getInternalStoragePath()
         val accountRecipeDir = File(externalDir, "recipes/$accountName/")
         // setRecipeDir is a suspend fun (DataStore-backed); runBlocking
         // here is safe since we're already off the main thread, and
         // matches how PreferenceData itself already bridges its own
         // suspend calls from non-suspend callers (see
         // setSyncServiceInterval).
         runBlocking { prefs.setRecipeDir(accountRecipeDir.absolutePath) }
         prefs.setSyncServiceEnabled()
         mainHandler.post { hostActivity?.onAccountSwitched() }
         SyncScheduler.syncNow(appContext)
      }
   }

   /**
    * Loads every account this app has access to, along with each one's
    * server-side display name. Runs on a background thread -- must not be
    * called from the main thread, since it performs network requests.
    */
   /**
    * Builds the initial account list from purely local data (AccountManager
    * + SSO account info) -- no network calls, so this returns essentially
    * instantly regardless of how many accounts are signed in. Display name
    * falls back to the login username and there's no avatar yet; both get
    * filled in by [enrichAccountsInBackground] afterward.
    */
   private fun loadAccountsFast(context: Context): List<AccountSwitcherItem> {
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

               AccountSwitcherItem(
                  accountName = ssoAccount.name,
                  displayName = ssoAccount.userId,
                  subtitle = "${ssoAccount.userId}@$hostname",
                  avatarBytes = AvatarCache.read(context, ssoAccount.name),
                  isCurrent = ssoAccount.name == currentAccountName
               )
            } catch (_: Exception) {
               // account known to AccountManager but not (or no longer)
               // accessible via SSO -- skip it rather than crash the list
               null
            }
         }
         // current account first, rest follow in their original order (stable
         // sort) -- fixed at this point and not re-sorted by enrichment
         // afterward, so rows don't jump around as their data fills in
         .sortedByDescending { it.isCurrent }
   }

   /**
    * Fetches each account's real display name and avatar and reports them
    * back via [onUpdated] as each one completes, one row at a time.
    *
    * Concurrent across accounts (not sequential): the previous version of
    * this bottom sheet opened a NextcloudAPI connection and made its
    * network calls for account 1, then account 2, then account 3 and so
    * on, one fully after another -- so with N accounts signed in, nothing
    * appeared until N round trips had each finished in turn. Firing them
    * all at once and letting whichever finishes first update its own row
    * immediately is both what makes multi-account switching feel fast and
    * closer to how the Nextcloud Files app's own switcher behaves.
    */
   private suspend fun enrichAccountsInBackground(
      context: Context,
      accounts: List<AccountSwitcherItem>,
      onUpdated: (AccountSwitcherItem) -> Unit
   ) {
      withContext(Dispatchers.IO) {
         accounts.map { item ->
            async {
               val enriched = enrichAccount(context, item)
               withContext(Dispatchers.Main) { onUpdated(enriched) }
            }
         }.awaitAll()
      }
   }

   /** The network part of loading one account's row: display name + avatar. */
   private fun enrichAccount(context: Context, item: AccountSwitcherItem): AccountSwitcherItem {
      val ssoAccount = try {
         AccountImporter.getSingleSignOnAccount(context, item.accountName)
      } catch (_: Exception) {
         return item
      }

      var api: NextcloudAPI? = null
      return try {
         api = NextcloudAPI(context, ssoAccount, GsonBuilder().create())
         // See AvatarFetcher's doc comment: fetched ourselves, rather than
         // handed to Glide as a URL, because nextcloud-commons:sso-glide's
         // Glide integration leaks the underlying network resource on
         // every load.
         val avatarBytes = AvatarFetcher.fetchAvatarBytes(api, ssoAccount)
         avatarBytes?.let { AvatarCache.write(context, ssoAccount.name, it) }
         val displayName = UserInfoAPI(api).getDisplayName() ?: item.displayName
         item.copy(displayName = displayName, avatarBytes = avatarBytes ?: item.avatarBytes)
      } catch (_: Exception) {
         item
      } finally {
         api?.close()
      }
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
      val avatarBytes: ByteArray?,
      val isCurrent: Boolean
   )

   private class AccountSwitcherAdapter(
      initialItems: List<AccountSwitcherItem>,
      private val onClick: (AccountSwitcherItem) -> Unit
   ) : RecyclerView.Adapter<AccountSwitcherAdapter.ViewHolder>() {

      private val items = initialItems.toMutableList()

      /** Replaces one row's data (matched by accountName) and redraws just that row. */
      fun updateItem(updated: AccountSwitcherItem) {
         val index = items.indexOfFirst { it.accountName == updated.accountName }
         if (index == -1) return

         val current = items[index]
         items[index] = updated

         // Skip the redraw if nothing user-visible actually changed: Glide
         // resets to .placeholder() before swapping in a new image on
         // every load, even when the bytes are identical -- so a redundant
         // rebind here would flicker every row's avatar back to the
         // placeholder icon on every single sheet open, undermining the
         // point of caching.
         val unchanged = current.displayName == updated.displayName &&
            current.subtitle == updated.subtitle &&
            current.isCurrent == updated.isCurrent &&
            bytesEqual(current.avatarBytes, updated.avatarBytes)
         if (!unchanged) {
            notifyItemChanged(index)
         }
      }

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
               .load(item.avatarBytes)
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
