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
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import it.niedermann.nextcloud.sso.glide.SingleSignOnUrl
import com.bumptech.glide.request.RequestOptions
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.gson.GsonBuilder
import com.nextcloud.android.sso.AccountImporter
import com.nextcloud.android.sso.api.NextcloudAPI
import com.odiousapps.kat.R
import com.odiousapps.kat.databinding.BottomSheetCopyToAccountBinding
import com.odiousapps.kat.databinding.ItemAccountSwitcherBinding
import com.odiousapps.kat.nextcloudapi.Accounts
import com.odiousapps.kat.nextcloudapi.RecipeCopier
import com.odiousapps.kat.nextcloudapi.UserInfoAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

private const val NEXTCLOUD_ACCOUNT_TYPE = "nextcloud"
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

      viewLifecycleOwner.lifecycleScope.launch {
         val accounts = withContext(Dispatchers.IO) { loadOtherAccounts(context) }

         _binding?.let { b ->
            b.loadingIndicator.visibility = View.GONE
            if (accounts.isEmpty()) {
               b.noOtherAccountsText.visibility = View.VISIBLE
            } else {
               b.accountList.visibility = View.VISIBLE
               b.accountList.adapter = CopyTargetAdapter(accounts) { item ->
                  copyRecipe(context, recipeJsonPath, recipeName, item)
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
   private fun loadOtherAccounts(context: Context): List<CopyTargetItem> {
      val currentAccountName = Accounts(context).getCurrentAccount()?.name

      return AccountManager.get(context).getAccountsByType(NEXTCLOUD_ACCOUNT_TYPE)
         .filter { it.name != currentAccountName }
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

               CopyTargetItem(
                  accountName = ssoAccount.name,
                  displayName = displayName,
                  subtitle = "${ssoAccount.userId}@$hostname",
                  avatarUrl = ssoAccount.url + "/index.php/avatar/" + Uri.encode(ssoAccount.userId) + "/64"
               )
            } catch (_: Exception) {
               // account known to AccountManager but not (or no longer)
               // accessible via SSO -- skip it rather than crash the list
               null
            }
         }
   }

   private data class CopyTargetItem(
      val accountName: String,
      val displayName: String,
      val subtitle: String,
      val avatarUrl: String,
   )

   private class CopyTargetAdapter(
      private val items: List<CopyTargetItem>,
      private val onClick: (CopyTargetItem) -> Unit
   ) : RecyclerView.Adapter<CopyTargetAdapter.ViewHolder>() {

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
               .load(SingleSignOnUrl(item.accountName, item.avatarUrl))
               .placeholder(R.drawable.ic_baseline_account_circle_24)
               .error(R.drawable.ic_baseline_account_circle_24)
               .apply(RequestOptions.circleCropTransform())
               .into(itemBinding.accountAvatar)
            itemBinding.root.setOnClickListener { onClick(item) }
         }
      }
   }
}
