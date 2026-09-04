/*
 * CopyToAccountBottomSheet.kt
 */
package com.odiousapps.kat.ui.copytoaccount

import android.accounts.AccountManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.gson.GsonBuilder
import com.nextcloud.android.sso.AccountImporter
import com.nextcloud.android.sso.api.NextcloudAPI
import com.odiousapps.kat.R
import com.odiousapps.kat.databinding.BottomSheetCopyToAccountBinding
import com.odiousapps.kat.databinding.ItemAccountSwitcherBinding
import com.odiousapps.kat.nextcloudapi.Accounts
import com.odiousapps.kat.nextcloudapi.AvatarCache
import com.odiousapps.kat.nextcloudapi.AvatarFetcher
import com.odiousapps.kat.nextcloudapi.RecipeCopier
import com.odiousapps.kat.nextcloudapi.UserInfoAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

private const val NEXTCLOUD_ACCOUNT_TYPE = "nextcloud"

/** ByteArray's own == is reference equality, not content -- this is null-safe content comparison. */
private fun bytesEqual(a: ByteArray?, b: ByteArray?): Boolean {
   if (a == null || b == null) return a == null && b == null
   return a.contentEquals(b)
}
private const val ARG_RECIPE_JSON_PATH = "recipe_json_path"
private const val ARG_RECIPE_NAME = "recipe_name"

/**
 * Lets the person pick one of their *other* Nextcloud accounts to copy
 * the currently-open recipe into. Reuses the account-loading/display
 * pattern from AccountSwitcherBottomSheet (same avatar+display-name
 * lookup), but excludes the account the recipe already belongs to, and
 * performs a copy rather than a switch on tap.
 *
 * @author MicMun
 * @version 1.0, 04.09.26
 */
class CopyToAccountBottomSheet : BottomSheetDialogFragment() {

   private var _binding: BottomSheetCopyToAccountBinding? = null
   private val binding get() = _binding!!

   companion object {
      /**
       * @param recipeJsonPath absolute path to the recipe's local recipe.json
       *   (DbRecipe.recipeCore.fileSystem.filePath)
       * @param recipeName the recipe's display name, used only in toasts
       */
      fun newInstance(recipeJsonPath: String, recipeName: String): CopyToAccountBottomSheet {
         return CopyToAccountBottomSheet().apply {
            arguments = Bundle().apply {
               putString(ARG_RECIPE_JSON_PATH, recipeJsonPath)
               putString(ARG_RECIPE_NAME, recipeName)
            }
         }
      }
   }

   override fun onCreateView(
      inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
   ): View {
      _binding = BottomSheetCopyToAccountBinding.inflate(inflater, container, false)
      return binding.root
   }

   override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
      super.onViewCreated(view, savedInstanceState)

      val context = requireContext()
      binding.accountList.layoutManager = LinearLayoutManager(context)

      val recipeJsonPath = requireArguments().getString(ARG_RECIPE_JSON_PATH)!!
      val recipeName = requireArguments().getString(ARG_RECIPE_NAME)!!

      // The initial render is local-only (AccountManager + SSO account
      // info, no network) so it appears essentially instantly regardless
      // of how many accounts are signed in -- matching how the Nextcloud
      // Files app's own account switcher shows up immediately, and how
      // AccountSwitcherBottomSheet now works too. Each row's real display
      // name and avatar are fetched afterward, in the background,
      // concurrently across accounts rather than one at a time.
      viewLifecycleOwner.lifecycleScope.launch {
         val accounts = withContext(Dispatchers.IO) { loadOtherAccountsFast(context) }

         _binding?.let { b ->
            b.loadingIndicator.visibility = View.GONE
            if (accounts.isEmpty()) {
               b.noOtherAccountsText.visibility = View.VISIBLE
            } else {
               val adapter = CopyTargetAdapter(accounts) { item ->
                  copyRecipe(context, recipeJsonPath, recipeName, item)
               }
               b.accountList.visibility = View.VISIBLE
               b.accountList.adapter = adapter

               withContext(Dispatchers.IO) {
                  accounts.map { item ->
                     async {
                        val enriched = enrichAccount(context, item)
                        withContext(Dispatchers.Main) { adapter.updateItem(enriched) }
                     }
                  }.awaitAll()
               }
            }
         }
      }
   }

   override fun onDestroyView() {
      super.onDestroyView()
      _binding = null
   }

   private fun copyRecipe(context: Context, recipeJsonPath: String, recipeName: String, target: CopyTargetItem) {
      dismiss()

      // Deliberately NOT using viewLifecycleOwner.lifecycleScope here: dismiss()
      // above tears this fragment's view down almost immediately, which cancels
      // that scope -- a coroutine launched on it would very likely be cancelled
      // before the network calls inside RecipeCopier ever finish, silently
      // dropping the copy. Using an application-scoped background executor
      // (matching Sync.kt's own pattern) lets the copy run to completion
      // regardless of what the UI does in the meantime.
      val appContext = context.applicationContext
      val mainHandler = Handler(Looper.getMainLooper())

      mainHandler.post {
         Toast.makeText(
            appContext,
            appContext.getString(R.string.copy_to_account_in_progress, recipeName, target.displayName),
            Toast.LENGTH_SHORT
         ).show()
      }

      Executors.newSingleThreadExecutor().submit {
         val result = RecipeCopier(appContext).copyToAccount(File(recipeJsonPath), target.accountName)

         val message = when (result) {
            is RecipeCopier.Result.Success ->
               appContext.getString(R.string.copy_to_account_success, recipeName, target.displayName)
            is RecipeCopier.Result.Failure ->
               appContext.getString(R.string.copy_to_account_failure, result.reason)
         }
         mainHandler.post {
            Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
         }
      }
   }

   /**
    * Loads every account this app has access to, except the one the
    * recipe currently belongs to, along with each one's server-side
    * display name. Runs on a background thread -- must not be called
    * from the main thread, since it performs network requests.
    */
   /**
    * Builds the initial account list (everyone except the one the recipe
    * already belongs to) from purely local data -- no network calls, so
    * this returns essentially instantly. Display name falls back to the
    * login username and there's no avatar yet; see enrichAccount, called
    * separately per row afterward.
    */
   private fun loadOtherAccountsFast(context: Context): List<CopyTargetItem> {
      val currentAccountName = Accounts(context).getCurrentAccount()?.name

      return AccountManager.get(context).getAccountsByType(NEXTCLOUD_ACCOUNT_TYPE)
         .filter { it.name != currentAccountName }
         .mapNotNull { account ->
            try {
               val ssoAccount = AccountImporter.getSingleSignOnAccount(context, account.name)
               val hostname = Uri.parse(ssoAccount.url).host ?: ssoAccount.url

               CopyTargetItem(
                  accountName = ssoAccount.name,
                  displayName = ssoAccount.userId,
                  subtitle = "${ssoAccount.userId}@$hostname",
                  avatarBytes = AvatarCache.read(context, ssoAccount.name)
               )
            } catch (_: Exception) {
               // account known to AccountManager but not (or no longer)
               // accessible via SSO -- skip it rather than crash the list
               null
            }
         }
   }

   /**
    * Fetches an account's real display name and avatar -- the network
    * part split out from loadOtherAccountsFast so it can run
    * concurrently per account in the background after the list is
    * already showing (previously this ran sequentially per account,
    * blocking the whole list from appearing until every account's
    * network call had finished in turn).
    */
   private fun enrichAccount(context: Context, item: CopyTargetItem): CopyTargetItem {
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

   private data class CopyTargetItem(
      val accountName: String,
      val displayName: String,
      val subtitle: String,
      val avatarBytes: ByteArray?,
   ) {
       override fun equals(other: Any?): Boolean {
           if (this === other) return true
           if (javaClass != other?.javaClass) return false

           other as CopyTargetItem

           if (accountName != other.accountName) return false
           if (displayName != other.displayName) return false
           if (subtitle != other.subtitle) return false
           if (!avatarBytes.contentEquals(other.avatarBytes)) return false

           return true
       }

       override fun hashCode(): Int {
           var result = accountName.hashCode()
           result = 31 * result + displayName.hashCode()
           result = 31 * result + subtitle.hashCode()
           result = 31 * result + (avatarBytes?.contentHashCode() ?: 0)
           return result
       }
   }

    private class CopyTargetAdapter(
      initialItems: List<CopyTargetItem>,
      private val onClick: (CopyTargetItem) -> Unit
   ) : RecyclerView.Adapter<CopyTargetAdapter.ViewHolder>() {

      private val items = initialItems.toMutableList()

      /** Replaces one row's data (matched by accountName) and redraws just that row. */
      fun updateItem(updated: CopyTargetItem) {
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

         fun bind(item: CopyTargetItem, onClick: (CopyTargetItem) -> Unit) {
            itemBinding.accountName.text = "${item.displayName} (${item.subtitle})"
            // this row is always a copy target, never "the current account"
            itemBinding.accountCheck.visibility = View.GONE
            Glide.with(itemBinding.root)
               .load(item.avatarBytes)
               .placeholder(R.drawable.ic_baseline_account_circle_24)
               .error(R.drawable.ic_baseline_account_circle_24)
               .apply(RequestOptions.circleCropTransform())
               .into(itemBinding.accountAvatar)
            itemBinding.root.setOnClickListener { onClick(item) }
         }
      }
   }
}
