/*
 * MainActivity.kt
 *
 * Copyright 2020 by MicMun
 */
package com.odiousapps.nextcloudcookbook.ui

import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SearchView.OnQueryTextListener
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
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
import com.bumptech.glide.request.RequestOptions
import com.nextcloud.android.sso.AccountImporter
import com.nextcloud.android.sso.exceptions.AccountImportCancelledException
import com.nextcloud.android.sso.exceptions.NextcloudFilesAppAccountNotFoundException
import com.nextcloud.android.sso.exceptions.NoCurrentAccountSelectedException
import com.nextcloud.android.sso.helper.SingleAccountHelper
import com.nextcloud.android.sso.model.SingleSignOnAccount
import com.nextcloud.android.sso.ui.UiExceptionManager
import com.odiousapps.nextcloudcookbook.MainApplication
import com.odiousapps.nextcloudcookbook.R
import com.odiousapps.nextcloudcookbook.data.CategoryFilter
import com.odiousapps.nextcloudcookbook.data.RecipeFilter
import com.odiousapps.nextcloudcookbook.data.SortValue
import com.odiousapps.nextcloudcookbook.databinding.ActivityMainBinding
import com.odiousapps.nextcloudcookbook.nextcloudapi.Accounts
import com.odiousapps.nextcloudcookbook.services.sync.SyncService
import com.odiousapps.nextcloudcookbook.settings.PreferenceData
import com.odiousapps.nextcloudcookbook.ui.recipelist.RecipeSearchCallback
import com.odiousapps.nextcloudcookbook.util.Filesystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*
import java.util.logging.Logger

/**
 * Main Activity of the app.
 *
 * @author MicMun
 * @version 2.0, 05.03.23
 */
class MainActivity : AppCompatActivity() {

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

      // toolbar
      setupToolbars()

      // drawer layout
      drawerLayout = binding.drawerLayout

      // settings
      val factory = CurrentSettingViewModelFactory(MainApplication.AppContext)
      val context = this

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
            Accounts(applicationContext).openAccountChooser(context)
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
      SyncService().startServiceScheduling(applicationContext)
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
         R.id.app_import_recipe -> {
            navController.navigate(R.id.downloadFormFragment)
            showToolbar(showToolbar = true, showSearch = false)
         }
         R.id.app_settings -> {
            navController.navigate(R.id.preferenceFragment)
            showToolbar(showToolbar = true, showSearch = false)
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
            startService(Intent(this, SyncService::class.java))
         }
      } catch (e: AccountImportCancelledException) {
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

   private fun updateProfilePicture() {
      try {
         val ssoAccount = SingleAccountHelper.getCurrentSingleSignOnAccount(applicationContext)
         Glide
            .with(this)
            .load(ssoAccount.url + "/index.php/avatar/" + Uri.encode(ssoAccount.userId) + "/64")
            .placeholder(R.drawable.ic_baseline_account_circle_24)
            .error(R.drawable.ic_baseline_account_circle_24)
            .apply(RequestOptions.circleCropTransform())
            .into(binding.accountSwitcher)

      } catch (e: NextcloudFilesAppAccountNotFoundException) {
         Logger.getLogger(this::class.java.name).severe("Please install the nextcloud app.")
      } catch (e: NoCurrentAccountSelectedException) {
         Logger.getLogger(this::class.java.name).severe("Please select an account.")
      }
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
