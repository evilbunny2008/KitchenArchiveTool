package com.odiousapps.kat.ui.recipelist

import android.accounts.AccountManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.postDelayed
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.odiousapps.kat.MainApplication
import com.odiousapps.kat.R
import com.odiousapps.kat.data.CategoryFilter
import com.odiousapps.kat.data.RecipeFilter
import com.odiousapps.kat.data.SortValue
import com.odiousapps.kat.databinding.FragmentRecipelistBinding
import com.odiousapps.kat.db.DbRecipeRepository
import com.odiousapps.kat.db.model.DbRecipePreview
import com.odiousapps.kat.reciever.LocalBroadcastReceiver
import com.odiousapps.kat.services.sync.SyncService
import com.odiousapps.kat.settings.PreferenceData
import com.odiousapps.kat.ui.CurrentSettingViewModel
import com.odiousapps.kat.ui.CurrentSettingViewModelFactory
import com.odiousapps.kat.ui.MainActivity
import com.odiousapps.kat.ui.copytoaccount.CopyToAccountBottomSheet
import com.odiousapps.kat.util.ConnectivityCheck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val NEXTCLOUD_ACCOUNT_TYPE = "nextcloud"

/**
 * Fragment for list of recipes.
 *
 * @author MicMun
 * @version 2.7, 13.03.23
 */
class RecipeListFragment : Fragment(), SwipeRefreshLayout.OnRefreshListener, RecipeSearchCallback {
   private lateinit var binding: FragmentRecipelistBinding
   private lateinit var recipesViewModel: RecipeListViewModel
   private lateinit var settingViewModel: CurrentSettingViewModel
   private lateinit var adapter: RecipeListAdapter

   private lateinit var mLocalBroadcastManager: LocalBroadcastManager
   private lateinit var mLocalBroadcastReceiver: LocalBroadcastReceiver
   private var mAutoRefreshList = false

   private var sortDialog: AlertDialog? = null
   private var currentSort: SortValue? = null

   private var isLoaded: Boolean = false

   override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
      binding = DataBindingUtil.inflate(inflater, R.layout.fragment_recipelist, container, false)

      binding.swipeContainer.setOnRefreshListener(this)
      val recipeListViewModelFactory = RecipeListViewModelFactory(requireActivity().application)
      recipesViewModel = ViewModelProvider(this, recipeListViewModelFactory)[RecipeListViewModel::class.java]
      val factory = CurrentSettingViewModelFactory(MainApplication.AppContext)
      settingViewModel =
         ViewModelProvider(MainApplication.AppContext, factory)[CurrentSettingViewModel::class.java]
      binding.lifecycleOwner = viewLifecycleOwner


      (activity as MainActivity).setRecipeSearchCallback(this)

      recipesViewModel.isUpdating.observe(viewLifecycleOwner) {
         it?.let { isUpdating ->
            binding.swipeContainer.isRefreshing = isUpdating
         }
      }

      recipesViewModel.isLoaded.observe(viewLifecycleOwner) {
         it?.let {
            isLoaded = it
         }
      }

      (activity as MainActivity?)?.showToolbar(
         showToolbar = true,
         showSearch = true,
         showSort = true
      )

      initializeRecipeList()

      val asyncFilter = (activity as MainActivity?)?.getAsyncFilter()
      if (asyncFilter != null) {
         searchRecipes(asyncFilter)
         (activity as MainActivity?)?.setVisualSearchTerm(asyncFilter.query, true)
         (activity as MainActivity?)?.setAsyncFilter(null)
      } else {
         searchCategory(CategoryFilter(CategoryFilter.CategoryFilterOption.ALL_CATEGORIES))
      }

      setupBroadcastListener()
      return binding.root
   }

   @Deprecated(
      "Deprecated in Java",
      ReplaceWith(
         "@Suppress(\"DEPRECATION\") super.onActivityCreated(savedInstanceState)",
         "androidx.fragment.app.Fragment"
      )
   )
   override fun onActivityCreated(savedInstanceState: Bundle?) {
      @Suppress("DEPRECATION")
      super.onActivityCreated(savedInstanceState)
   }

   private fun initializeRecipeList() {
      binding.recipeListViewModel = recipesViewModel
      binding.lifecycleOwner = viewLifecycleOwner

      // data adapter
      adapter = RecipeListAdapter(
         RecipeListListener(
            clickListener = { recipeName -> recipesViewModel.onRecipeClicked(recipeName) },
            longClickListener = { recipe, anchorView -> showRecipeContextMenu(recipe, anchorView) }
         ),
         DbRecipeRepository.getInstance(requireActivity().application)
      )
      binding.recipeList.adapter = adapter

      // settings
      adapter.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY

      recipesViewModel.navigateToRecipe.observe(viewLifecycleOwner) { recipe ->
         recipe?.let {
            val navController = this.findNavController()
            // A rapid double-tap on a recipe row can fire this click
            // handler twice before onRecipeNavigated() below clears the
            // pending navigation event: the first tap already navigates
            // away from recipeListFragment, so by the time the second one
            // arrives here, the current destination is recipeDetailFragment
            // -- and this specific action only exists from
            // recipeListFragment, so invoking it again throws
            // IllegalArgumentException instead of silently doing nothing.
            if (navController.currentDestination?.id == R.id.recipeListFragment) {
               navController.navigate(
                  RecipeListFragmentDirections.actionRecipeListFragmentToRecipeDetailFragment(recipe)
               )
            }
            recipesViewModel.onRecipeNavigated()
         }
      }

      lifecycleScope.launch {
         repeatOnLifecycle(Lifecycle.State.RESUMED) {
            settingViewModel.sorting.collect { sort ->
               currentSort = SortValue.getByValue(sort)
               recipesViewModel.sortList(currentSort!!)
               loadData()
            }
         }
      }

      lifecycleScope.launch {
         repeatOnLifecycle(Lifecycle.State.RESUMED) {
            settingViewModel.storageAccessed.collect { storageAccessed ->
               if (storageAccessed) {
                  settingViewModel.recipeDirectory.collect { dir ->
                     if (!isLoaded || PreferenceData.getInstance().isSyncServiceEnabled()) {
                        recipesViewModel.initRecipes(dir)
                        // Rebuilds the list query against the (possibly new,
                        // e.g. after switching accounts) directory -- without
                        // this, the previously active query subscription
                        // keeps filtering by whatever directory was in effect
                        // when it was originally created, so the visible list
                        // wouldn't reflect the account switch at all.
                        loadData()
                     }
                  }
               }
            }
         }
      }

      settingViewModel.category.observe(viewLifecycleOwner) { catFilter ->
         catFilter?.let {
            // filter recipes to set category
            recipesViewModel.filterRecipesByCategory(it)
            setCategoryTitle(it)
            loadData()

            settingViewModel.categoryChanged.observe(viewLifecycleOwner) { changed ->
               changed?.let { c ->
                  if (c) {
                     binding.recipeList.postDelayed(250) {
                        binding.recipeList.smoothScrollToPosition(0)
                     }
                     settingViewModel.resetCategoryChanged()
                  }
               }
            }
         }
      }

      recipesViewModel.categories.observe(viewLifecycleOwner) { categories ->
         categories?.let {
            var order = 1
            val activity = requireActivity() as MainActivity
            val menu = activity.getMenu().findItem(R.id.submenu_item).subMenu

            if (menu != null) {
               menu.removeGroup(R.id.menu_categories_group)
               categories.forEach { category ->
                  menu.add(R.id.menu_categories_group, category.hashCode(), order++, category)
                     .setIcon(R.drawable.ic_food)
               }
            }
         }
      }

      loadData()
   }

   /**
    * Loads the current data.
    */
   private fun loadData() {
      recipesViewModel.loadRecipes()
      recipesViewModel.recipes.observe(viewLifecycleOwner) { recipes ->
         recipes?.let {
            adapter.submitList(it)
         }
         if (recipes.isNullOrEmpty()) {
            if (R.id.emptyConstraint == binding.switcher.nextView.id)
               binding.switcher.showNext()
         } else if (R.id.titleConstraint == binding.switcher.nextView.id) {
            binding.switcher.showNext()
         }
      }
   }

   /**
    * Sets the category title.
    *
    * @param categoryFilter Filter of the category.
    */
   private fun setCategoryTitle(categoryFilter: CategoryFilter) {
      Log.d("RecipeListFragment", "setCategoryTitle: categoryFilter= $categoryFilter")
      // set title in text view headline
      val catTitle = binding.categoryTitle
      if (categoryFilter.type == CategoryFilter.CategoryFilterOption.ALL_CATEGORIES)
         catTitle.visibility = View.GONE
      else {
         catTitle.visibility = View.VISIBLE
         catTitle.text = if (categoryFilter.type == CategoryFilter.CategoryFilterOption.UNCATEGORIZED)
            getString(R.string.text_uncategorized)
         else categoryFilter.name
      }
   }

   private fun showSortOptions() {
      val sortNames = resources.getStringArray(R.array.sort_names)
      val builder = AlertDialog.Builder(requireContext())
      builder.setTitle(R.string.menu_sort_title)
      val sortValue = currentSort ?: SortValue.NAME_A_Z
      builder.setSingleChoiceItems(sortNames, sortValue.sort) { _: DialogInterface, which: Int ->
         settingViewModel.setSorting(which, (activity as MainActivity))
         sortDialog?.dismiss()
         sortDialog = null
         binding.recipeList.postDelayed(200) {
            binding.recipeList.smoothScrollToPosition(0)
         }
      }
      builder.setOnDismissListener { sortDialog = null }
      sortDialog = builder.show()
   }

   //todo: think about how to make this more elegant.
   //also it seems quickly refreshing breaks the database.
   private fun onRefreshAndReschedule() {
      if (!mAutoRefreshList) {
         return
      }
      Handler(Looper.getMainLooper()).postDelayed({
         CoroutineScope(Dispatchers.Main).launch {
            settingViewModel.storageAccessed.collect { storageAccessed ->
               if (storageAccessed) {
                  settingViewModel.recipeDirectory.collect { dir ->
                     if (dir != recipesViewModel.getRecipeDir()) {
                        recipesViewModel.initRecipes(dir, true)
                     } else {
                        recipesViewModel.initRecipes(hidden = true)
                     }
                  }
               }
            }
         }
         onRefreshAndReschedule()
      }, 500)
   }

   private fun doSync(context: Context?) {
      // load recipes from files
      context?.startForegroundService(Intent(context, SyncService::class.java))
   }

   private fun onSyncFailure(id: Int) {
      binding.swipeContainer.isRefreshing = false
      Toast.makeText(
         requireContext(), getString(R.string.error_sync, getString(id)), Toast.LENGTH_LONG
      ).show()
   }

   override fun onRefresh() {
      recipesViewModel.removeDuplicateRecipes()
      if (PreferenceData.getInstance().isWifiOnly()) {
         if (ConnectivityCheck.isConnectedToWifi(context)) {
            doSync(context)
         } else {
            onSyncFailure(R.string.error_only_wifi)
         }
      } else if (ConnectivityCheck.isConnected(context)) {
         doSync(context)
      } else {
         onSyncFailure(R.string.error_no_network)
      }
   }

   override fun onPause() {
      dismissBroadcastListener()
      sortDialog?.dismiss()
      super.onPause()
   }

   override fun onResume() {
      setupBroadcastListener()
      loadData()
      super.onResume()
   }

   override fun searchRecipes(filter: RecipeFilter) {
      if (filter.type == RecipeFilter.QueryType.QUERY_NAME && filter.query == "") {
         setCategoryTitle(CategoryFilter(CategoryFilter.CategoryFilterOption.ALL_CATEGORIES))
      }
      recipesViewModel.search(filter)
      loadData()
   }

   override fun searchCategory(filter: CategoryFilter) {
      recipesViewModel.filterRecipesByCategory(filter)
      recipesViewModel.search(null)
      loadData()
   }

   override fun showSortSelector() {
      showSortOptions()
   }

   fun notifyUpdate(updating: Boolean) {
      // Go in there only once to avoid double and more entries of the same ingredients or instructions
      // (https://codeberg.org/MicMun/nextcloud-cookbook/issues/92)
      if (updating != mAutoRefreshList) {
         mAutoRefreshList = updating

         // Dont use this for now.
         // onRefreshAndReschedule()

         if (!updating) {
            CoroutineScope(Dispatchers.Main).launch {
               settingViewModel.storageAccessed.collect { storageAccessed ->
                  if (storageAccessed) {
                     settingViewModel.recipeDirectory.collect { dir ->
                        if (dir != recipesViewModel.getRecipeDir()) {
                           recipesViewModel.initRecipes(dir, true)
                        } else {
                           recipesViewModel.initRecipes(hidden = true)
                        }
                     }
                  }
               }
            }
         }

         binding.swipeContainer.isRefreshing = updating
      }
   }

   private fun setupBroadcastListener() {
      mLocalBroadcastManager = LocalBroadcastManager.getInstance(this.requireContext())
      mLocalBroadcastReceiver = LocalBroadcastReceiver(this)
      val intentFilter = IntentFilter()
      intentFilter.addAction(SyncService.SYNC_SERVICE_UPDATE_BROADCAST)
      mLocalBroadcastManager.registerReceiver(mLocalBroadcastReceiver, intentFilter)
   }

   private fun dismissBroadcastListener() {
      mLocalBroadcastManager.unregisterReceiver(mLocalBroadcastReceiver)
   }

   /**
    * Shows the long-press popup menu for a single recipe row. Currently
    * just "copy to account", greyed out when there's no other account to
    * copy into -- AccountManager.getAccountsByType() is a local, in-memory
    * lookup (no network call), so this can run synchronously right here
    * rather than needing a background thread.
    */
   private fun showRecipeContextMenu(recipe: DbRecipePreview, anchorView: View) {
      val popup = PopupMenu(requireContext(), anchorView)
      popup.menuInflater.inflate(R.menu.recipe_list_item_menu, popup.menu)

      val hasOtherAccount = AccountManager.get(requireContext())
         .getAccountsByType(NEXTCLOUD_ACCOUNT_TYPE).size > 1
      popup.menu.findItem(R.id.menu_copy_recipe).isEnabled = hasOtherAccount

      popup.setOnMenuItemClickListener { item ->
         when (item.itemId) {
            R.id.menu_copy_recipe -> {
               openCopyToAccountSheet(recipe)
               true
            }
            else -> false
         }
      }
      popup.show()
   }

   /**
    * The list only carries a DbRecipePreview (id/name/description/thumb/
    * starred, see DbRecipePreview.DBFIELDS) -- CopyToAccountBottomSheet
    * needs the recipe's local recipe.json path, which isn't part of that
    * preview, so this looks up the full DbRecipe first.
    */
   private fun openCopyToAccountSheet(recipe: DbRecipePreview) {
      val repository = DbRecipeRepository.getInstance(requireActivity().application)
      viewLifecycleOwner.lifecycleScope.launch {
         val fullRecipe = withContext(Dispatchers.IO) { repository.getRecipeSync(recipe.id) }
         fullRecipe?.let {
            CopyToAccountBottomSheet.newInstance(
               recipeJsonPath = it.recipeCore.fileSystem.filePath,
               recipeName = it.recipeCore.name
            ).show(childFragmentManager, "copyToAccount")
         }
      }
   }
}
