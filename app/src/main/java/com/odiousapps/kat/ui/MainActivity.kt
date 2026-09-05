/*
 * MainActivity.kt
 *
 * Copyright 2020 by MicMun
 */
package com.odiousapps.kat.ui

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SearchView.OnQueryTextListener
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.databinding.DataBindingUtil
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.ui.NavigationUI
import androidx.preference.PreferenceManager
import com.bumptech.glide.Glide
import com.odiousapps.kat.nextcloudapi.Accounts
import com.odiousapps.kat.nextcloudapi.AvatarCache
import com.odiousapps.kat.nextcloudapi.AvatarFetcher
import com.odiousapps.kat.nextcloudapi.RecipeImportClient
import com.odiousapps.kat.nextcloudapi.RecipeImportCredentialStore
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.nextcloud.android.sso.AccountImporter
import com.nextcloud.android.sso.exceptions.AccountImportCancelledException
import com.nextcloud.android.sso.exceptions.NextcloudFilesAppAccountNotFoundException
import com.nextcloud.android.sso.exceptions.NoCurrentAccountSelectedException
import com.nextcloud.android.sso.helper.SingleAccountHelper
import com.nextcloud.android.sso.model.SingleSignOnAccount
import com.nextcloud.android.sso.ui.UiExceptionManager
import com.odiousapps.kat.MainApplication
import com.odiousapps.kat.R
import com.odiousapps.kat.data.CategoryFilter
import com.odiousapps.kat.data.RecipeFilter
import com.odiousapps.kat.data.SortValue
import com.odiousapps.kat.databinding.ActivityMainBinding
import com.odiousapps.kat.services.sync.SyncScheduler
import com.odiousapps.kat.settings.PreferenceData
import com.odiousapps.kat.ui.accountswitcher.AccountSwitcherBottomSheet
import com.odiousapps.kat.ui.recipeimport.RecipeImportLoginActivity
import com.odiousapps.kat.ui.recipelist.RecipeSearchCallback
import com.odiousapps.kat.util.Filesystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.logging.Logger

/**
 * Main Activity of the app.
 *
 * @author MicMun
 * @version 2.0, 05.03.23
 */
class MainActivity : AppCompatActivity(), AccountSwitcherBottomSheet.AccountSwitcherHost {

   companion object {
      const val THEME_PREFERENCE_DEFAULT = 2
   }

   private lateinit var binding: ActivityMainBinding
   private lateinit var drawerLayout: DrawerLayout
   private lateinit var currentSettingViewModel: CurrentSettingViewModel
   private lateinit var preferenceData: PreferenceData

   private var asyncFilter: RecipeFilter? = null
   private var mRecipeSearchCallback: RecipeSearchCallback? = null

   private var mAllowSearchToTrigger = true

   override fun onCreate(savedInstanceState: Bundle?) {
      preferenceData = PreferenceData.getInstance()

      if (!preferenceData.isInitializedSync()) {
         lifecycleScope.launch(Dispatchers.Main) {
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(application)
            preferenceData.migrateSharedPreferences(sharedPreferences)
            if (!preferenceData.isInitializedSync()) {
               preferenceData.setSort(SortValue.NAME_A_Z.sort)
               preferenceData.setTheme(THEME_PREFERENCE_DEFAULT)
               preferenceData.setStorageAccessed(false)
            }
         }
      }


      setTheme(R.style.AppTheme_Light)
      // apply theme
      when (preferenceData.getThemeSync()) {
         0 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
         1 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
         2 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
      }

      super.onCreate(savedInstanceState)
      binding = DataBindingUtil.setContentView(this, R.layout.activity_main)

      // targetSdk 35+ enforces edge-to-edge display, so content draws behind
      // the status bar by default -- without this, the app bar's icons
      // (menu/search/sort/account) render underneath the system status bar.
      // Pads the app bar down by exactly the status bar's height instead.
      ViewCompat.setOnApplyWindowInsetsListener(binding.appBar) { view, windowInsets ->
         val statusBarInsets = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
         view.updatePadding(top = statusBarInsets.top)
         windowInsets
      }

      // Same edge-to-edge issue as the app bar above, but for the bottom
      // navigation/gesture bar -- without this, the last row of the recipe
      // list (and any other screen hosted here) renders underneath it.
      // Applied to the nav host container itself rather than per-fragment,
      // so every screen in the nav graph gets this fix in one place.
      ViewCompat.setOnApplyWindowInsetsListener(binding.navHostFragment) { view, windowInsets ->
         val navigationBarInsets = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
         view.updatePadding(bottom = navigationBarInsets.bottom)
         windowInsets
      }

      // toolbar
      setupToolbars()

      // drawer layout
      drawerLayout = binding.drawerLayout

      // settings
      val factory = CurrentSettingViewModelFactory(MainApplication.AppContext)

      with(binding) {
         currentSettingViewModel =
            ViewModelProvider(MainApplication.AppContext, factory)[CurrentSettingViewModel::class.java]
         navView.setNavigationItemSelectedListener { item ->
            setVisualSearchTerm("", false)
            when (item.itemId) {
               R.id.menu_all_categories -> currentSettingViewModel.setNewCategory(
                  CategoryFilter(
                     CategoryFilter.CategoryFilterOption.ALL_CATEGORIES
                  )
               )

               R.id.menu_uncategorized -> currentSettingViewModel.setNewCategory(
                  CategoryFilter(
                     CategoryFilter.CategoryFilterOption.UNCATEGORIZED
                  )
               )

               else -> {
                  if (item.groupId == R.id.menu_categories_group) {
                     currentSettingViewModel.setNewCategory(
                        CategoryFilter(
                           CategoryFilter.CategoryFilterOption.CATEGORY, item.title.toString()
                        )
                     )
                  }
               }
            }
            handleNavigationDrawerSelection(item.itemId)
            drawerLayout.closeDrawers()
            true
         }


         searchText.setOnClickListener {
            searchToolbar.visibility = View.VISIBLE
            normalToolbar.visibility = View.GONE
            searchbar.isIconified = false
         }

         backButton.setOnClickListener {
            searchToolbar.visibility = View.GONE
            normalToolbar.visibility = View.VISIBLE
         }

         sortorder.setOnClickListener {
            mRecipeSearchCallback?.showSortSelector()
         }

         accountSwitcher.setOnClickListener {
            AccountSwitcherBottomSheet().show(supportFragmentManager, "account_switcher")
         }

         searchbar.setOnQueryTextListener(object : OnQueryTextListener,
            android.widget.SearchView.OnQueryTextListener {

            override fun onQueryTextChange(qString: String): Boolean {
               if (mAllowSearchToTrigger) {
                  search(qString)
               }
               return true
            }

            override fun onQueryTextSubmit(qString: String): Boolean {
               if (mAllowSearchToTrigger) {
                  search(qString)
               }
               return true
            }
         })
      }
      SyncScheduler.reschedule(applicationContext)
      updateProfilePicture()

      if (preferenceData.isSyncServiceEnabled()) {
         lifecycleScope.launch(Dispatchers.Main) {
            preferenceData.setStorageAccessed(true)
         }
      }
      handleIntent(intent)
   }

   private fun handleNavigationDrawerSelection(item: Int) {
      val navHostFragment = supportFragmentManager.findFragmentById(R.id.navHostFragment) as NavHostFragment
      val navController = navHostFragment.findNavController()
      when (item) {
         R.id.menu_search_extended -> {
            navController.navigate(R.id.searchFormFragment)
            showToolbar(showToolbar = true, showSearch = false)
         }
         R.id.app_settings -> {
            navController.navigate(R.id.preferenceFragment)
            showToolbar(showToolbar = true, showSearch = false)
         }
         R.id.app_import_recipe -> {
            showImportRecipeDialog()
         }
         R.id.menu_all_categories, R.id.menu_uncategorized -> {
            navController.navigate(R.id.recipeListFragment)
            showToolbar(showToolbar = true, showSearch = true)
         }
         else -> {
            navController.navigate(R.id.recipeListFragment)
            showToolbar(showToolbar = true, showSearch = true)
         }
      }
   }

   override fun onNewIntent(intent: Intent) {
      super.onNewIntent(intent)
      handleIntent(intent)
   }

   // --- Recipe import from URL ---------------------------------------------
   //
   // Flow: ask for a URL -> if this account doesn't already have a stored
   // app password for the import bridge, send the user through
   // RecipeImportLoginActivity's WebView to get one (Nextcloud's own Login
   // Flow v2, see that class and NextcloudLoginFlow) -> POST hostname
   // /username/app-password/recipe URL to the configured bridge script
   // (RecipeImportClient) -> show the result.

   /** Holds the URL the user asked to import while RecipeImportLoginActivity is running. */
   private var pendingRecipeImportUrl: String? = null
   /** Holds the service (bridge script) URL for the same pending import. */
   private var pendingRecipeImportServiceUrl: String? = null

   private val recipeImportLoginLauncher = registerForActivityResult(
      ActivityResultContracts.StartActivityForResult()
   ) { result ->
      val recipeUrl = pendingRecipeImportUrl
      val serviceUrl = pendingRecipeImportServiceUrl
      pendingRecipeImportUrl = null
      pendingRecipeImportServiceUrl = null

      if (result.resultCode == RESULT_OK && recipeUrl != null && serviceUrl != null) {
         // RecipeImportLoginActivity only signals success/failure -- it
         // deliberately doesn't hand the credential back via Intent
         // extras (see its own doc comment), so it's read back from
         // encrypted storage here instead.
         val account = Accounts(this).getCurrentAccount()
         val credentials = account?.let { RecipeImportCredentialStore.get(this, it.name) }
         if (credentials != null) {
            performRecipeImport(serviceUrl, credentials.server, credentials.loginName, credentials.appPassword, recipeUrl)
         }
      }
      // Cancelled or failed: RecipeImportLoginActivity has already shown
      // its own error/timeout message in that case, nothing more to do here.
   }

   private fun showImportRecipeDialog() {
      // Pre-filled from whatever was entered last time (see the positive
      // button below, which saves it back for next time) -- the bridge
      // script's own URL doesn't need to be hosted anywhere near
      // Nextcloud itself (in fact, it's better if it isn't -- Nextcloud's
      // own file-integrity checker flags unexpected files in its
      // directory tree during upgrades), so this is just remembered here
      // rather than requiring a trip to Settings first.
      val serviceUrlEditText = EditText(this).apply {
         hint = getString(R.string.recipe_import_service_url_hint)
         inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
         setText(PreferenceData.getInstance().getRecipeImportUrlSync())
      }
      val recipeUrlEditText = EditText(this).apply {
         hint = getString(R.string.recipe_import_dialog_hint)
         inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
      }

      // Plain EditTexts have no built-in margin from a dialog's edges or
      // spacing from each other; a padded vertical LinearLayout handles both.
      val margin = (24 * resources.displayMetrics.density).toInt()
      val spacing = (12 * resources.displayMetrics.density).toInt()
      val container = LinearLayout(this).apply {
         orientation = LinearLayout.VERTICAL
         setPadding(margin, margin / 2, margin, 0)
         addView(serviceUrlEditText)
         addView(
            recipeUrlEditText,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
               topMargin = spacing
            }
         )
      }

      AlertDialog.Builder(this)
         .setTitle(R.string.recipe_import_dialog_title)
         .setView(container)
         .setPositiveButton(R.string.recipe_import_dialog_positive) { _, _ ->
            val serviceUrl = serviceUrlEditText.text.toString().trim()
            val recipeUrl = recipeUrlEditText.text.toString().trim()
            if (serviceUrl.isNotEmpty() && recipeUrl.isNotEmpty()) {
               // Persisted for next time's pre-fill -- fire-and-forget,
               // doesn't block the actual import below, which uses the
               // value entered just now directly rather than reading it
               // back from storage.
               lifecycleScope.launch(Dispatchers.IO) {
                  PreferenceData.getInstance().setRecipeImportUrl(serviceUrl)
               }
               startRecipeImport(serviceUrl, recipeUrl)
            }
         }
         .setNegativeButton(android.R.string.cancel, null)
         .show()
   }

   private fun startRecipeImport(serviceUrl: String, recipeUrl: String) {
      val account = Accounts(this).getCurrentAccount()
      if (account == null) {
         Toast.makeText(this, R.string.current_account_not_found_exception_message, Toast.LENGTH_LONG).show()
         return
      }

      val cached = RecipeImportCredentialStore.get(this, account.name)
      if (cached != null) {
         performRecipeImport(serviceUrl, cached.server, cached.loginName, cached.appPassword, recipeUrl)
      } else {
         pendingRecipeImportUrl = recipeUrl
         pendingRecipeImportServiceUrl = serviceUrl
         recipeImportLoginLauncher.launch(
            RecipeImportLoginActivity.newIntent(this, account.url, account.name)
         )
      }
   }

   private fun performRecipeImport(serviceUrl: String, hostname: String, username: String, password: String, recipeUrl: String) {
      Toast.makeText(this, R.string.recipe_import_in_progress, Toast.LENGTH_SHORT).show()

      lifecycleScope.launch {
         val result = withContext(Dispatchers.IO) {
            RecipeImportClient.importRecipe(serviceUrl, hostname, username, password, recipeUrl)
         }
         when (result) {
            is RecipeImportClient.Result.Success ->
               Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
            is RecipeImportClient.Result.Failure -> {
               // A 401/403 from the bridge script means the stored app
               // password was revoked server-side (e.g. from Nextcloud's
               // own Devices & sessions page) -- clear it so the next
               // attempt goes through RecipeImportLoginActivity again for
               // a fresh one, rather than repeating the same failure
               // forever.
               if (result.reason.contains("401") || result.reason.contains("403")) {
                  Accounts(this@MainActivity).getCurrentAccount()?.let {
                     RecipeImportCredentialStore.clear(this@MainActivity, it.name)
                  }
               }
               Toast.makeText(
                  this@MainActivity,
                  getString(R.string.recipe_import_failure, result.reason),
                  Toast.LENGTH_LONG
               ).show()
            }
         }
      }
   }

   fun getMenu(): Menu {
      return binding.navView.menu
   }

   override fun onSupportNavigateUp(): Boolean {
      val navController = this.findNavController(R.id.navHostFragment)
      return NavigationUI.navigateUp(navController, drawerLayout)
   }

   private fun setupToolbars() {
      binding.menuButton.setOnClickListener {
         binding.drawerLayout.openDrawer(
            GravityCompat.START
         )
      }
   }

   /**
    * Handle intent for search.
    *
    * @param intent incoming intent.
    */
   private fun handleIntent(intent: Intent?) {
      if (Intent.ACTION_SEARCH == intent?.action) {
         val query = intent.getStringExtra(SearchManager.QUERY)
         if (query != null) {
            search(query)
         }
      }
   }

   private fun search(query: String) {
      val filter = RecipeFilter(RecipeFilter.QueryType.QUERY_NAME, query)
      mRecipeSearchCallback?.searchRecipes(filter)
   }

   /**
    * This filter will be applied the next time the recipe-list is opened.
    * This is used for the advanced SearchFormFragment
    */
   fun setAsyncFilter(filter: RecipeFilter?) {
      asyncFilter = filter
   }

   /**
    * Get the filter set. Returns null if no filter exists.
    */
   fun getAsyncFilter(): RecipeFilter? {
      return asyncFilter
   }

   fun setVisualSearchTerm(value: String, focus: Boolean) {
      //Disable and reenable query trigger
      mAllowSearchToTrigger = false
      binding.searchbar.setQuery(value, false)
      mAllowSearchToTrigger = true

      if (focus) {
         binding.searchText.performClick()
      }
   }

   fun showToolbar(showToolbar: Boolean, showSearch: Boolean = true, showSort: Boolean = true) {
      if (showToolbar) {
         binding.appBar.visibility = View.VISIBLE
      } else {
         binding.appBar.visibility = View.GONE
      }
      if (showSearch) {
         binding.searchText.visibility = View.VISIBLE
      } else {
         binding.searchText.visibility = View.INVISIBLE
      }
      if (showSort) {
         binding.sortorder.visibility = View.VISIBLE
      } else {
         binding.sortorder.visibility = View.INVISIBLE
      }
   }

   fun setRecipeSearchCallback(callback: RecipeSearchCallback?) {
      mRecipeSearchCallback = callback
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

            updateProfilePicture()
            SyncScheduler.syncNow(applicationContext)
         }
      } catch (_: AccountImportCancelledException) {
      }
   }

   override fun onRequestPermissionsResult(
      requestCode: Int,
      permissions: Array<out String>,
      grantResults: IntArray
   ) {
      super.onRequestPermissionsResult(requestCode, permissions, grantResults)
      AccountImporter.onRequestPermissionsResult(requestCode, permissions, grantResults, this)
   }

   override fun onAccountSwitched() {
      updateProfilePicture()
   }

   private fun updateProfilePicture() {
      // SingleAccountHelper.getCurrentSingleSignOnAccount() is documented
      // @WorkerThread (it does blocking AccountManager/Binder IPC calls) --
      // this was previously being called directly on the main thread here.
      lifecycleScope.launch {
         val ssoAccount = try {
            withContext(Dispatchers.IO) {
               SingleAccountHelper.getCurrentSingleSignOnAccount(applicationContext)
            }
         } catch (_: NextcloudFilesAppAccountNotFoundException) {
            Logger.getLogger(this::class.java.name).severe("Please install the nextcloud app.")
            return@launch
         } catch (_: NoCurrentAccountSelectedException) {
            Logger.getLogger(this::class.java.name).severe("Please select an account.")
            return@launch
         }

         // long-press indicator of the current account -- doesn't affect the
         // tap behavior, which opens the system account chooser as before
         binding.accountSwitcher.tooltipText = ssoAccount.name

         // Shows instantly from a previous fetch (AvatarCache is keyed by
         // account name, so unlike Glide's own cache -- deliberately left
         // disabled below -- there's no risk of a stale image from a
         // *different* account leaking through here after a switch).
         val cachedBytes = withContext(Dispatchers.IO) { AvatarCache.read(applicationContext, ssoAccount.name) }
         if (cachedBytes != null) {
            loadAvatar(cachedBytes)
         }

         // Refreshed in the background regardless of whether a cached
         // image was just shown, so the cache (and the displayed image,
         // if it actually changed) stay current without ever blocking on
         // the network first. See AvatarFetcher's doc comment: fetched
         // ourselves, rather than handed to Glide as a URL/SingleSignOnUrl,
         // because nextcloud-commons:sso-glide's Glide integration leaks
         // the underlying network resource on every load.
         val freshBytes = withContext(Dispatchers.IO) {
            val api = Accounts(applicationContext).getApiToAccount()
             api.use { api ->
                 api?.let { AvatarFetcher.fetchAvatarBytes(it, ssoAccount) }
             }
         }
         if (freshBytes != null) {
            withContext(Dispatchers.IO) { AvatarCache.write(applicationContext, ssoAccount.name, freshBytes) }
            // Skip the reload entirely if nothing actually changed: Glide
            // resets the target to .placeholder() before swapping in a new
            // image on every load, even when the bytes are identical --
            // triggering a visible flicker back to the placeholder icon on
            // every single screen open, which defeats the point of caching
            // (a correct cache hit would otherwise be invisible, since the
            // avatar was already showing correctly from cachedBytes above).
            if (cachedBytes == null || !freshBytes.contentEquals(cachedBytes)) {
               loadAvatar(freshBytes)
            }
         } else if (cachedBytes == null) {
            // nothing cached and the fetch failed -- fall back to the placeholder
            loadAvatar(null)
         }
      }
   }

   private fun loadAvatar(bytes: ByteArray?) {
      Glide
         .with(this@MainActivity)
         // Both Glide cache layers are deliberately skipped: AvatarCache
         // above already handles persistence (correctly keyed per
         // account), so Glide caching these bytes too would just be
         // redundant -- and a stale image from a *different* account
         // silently lingering here after switching would be a real,
         // confusing correctness bug.
         .load(bytes)
         .skipMemoryCache(true)
         .diskCacheStrategy(DiskCacheStrategy.NONE)
         .placeholder(R.drawable.ic_baseline_account_circle_24)
         .error(R.drawable.ic_baseline_account_circle_24)
         .apply(RequestOptions.circleCropTransform())
         .into(binding.accountSwitcher)
   }

   fun setSortIcon(sort: SortValue) {
      val id = when (sort) {
         SortValue.NAME_A_Z -> R.drawable.sort_alphabetical_ascending
         SortValue.NAME_Z_A -> R.drawable.sort_alphabetical_descending
         SortValue.DATE_ASC -> R.drawable.sort_calendar_ascending
         SortValue.DATE_DESC -> R.drawable.sort_calendar_descending
         SortValue.TOTAL_TIME_ASC -> R.drawable.sort_clock_ascending_outline
         SortValue.TOTAL_TIME_DESC -> R.drawable.sort_clock_descending_outline
      }

      binding.sortorder.setImageDrawable(ContextCompat.getDrawable(this, id))
   }
}
