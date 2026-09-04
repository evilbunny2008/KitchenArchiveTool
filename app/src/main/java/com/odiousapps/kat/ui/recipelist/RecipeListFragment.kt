package com.odiousapps.kat.ui.recipelist

import android.accounts.AccountManager
import android.content.Context
import android.content.DialogInterface
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
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
import com.odiousapps.kat.nextcloudapi.RecipeDeleter
import com.odiousapps.kat.reciever.LocalBroadcastReceiver
import com.odiousapps.kat.services.sync.SyncScheduler
import com.odiousapps.kat.services.sync.SyncWorker
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
import java.io.File

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
            longClickListener = { recipe -> showRecipeContextMenu(recipe) }
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
      context?.let { SyncScheduler.syncNow(it) }
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
      syncIfPossible()
      super.onResume()
   }

   /**
    * Kicks off a background sync whenever the recipe list becomes visible
    * again -- returning from another screen, reopening the app, etc. --
    * not just on an explicit pull-to-refresh or account switch. Without
    * this, recently-changed server-side state (most notably: a recipe
    * just copied in from another account) wouldn't show up until the
    * person happened to trigger one of those two other paths, or the
    * periodic background sync eventually got around to it.
    *
    * Silently skipped if there's no network or the wifi-only setting
    * blocks it -- unlike onRefresh()'s explicit user action, which
    * surfaces a toast for those same cases, a passive resume trigger
    * shouldn't nag about connectivity the person didn't explicitly ask
    * to check. SyncScheduler.syncNow()'s own KEEP work policy already
    * prevents this from piling up duplicate syncs if one's already in
    * flight (e.g. right after switching accounts, which also syncs).
    */
   private fun syncIfPossible() {
      val ctx = context ?: return
      val hasNetwork = if (PreferenceData.getInstance().isWifiOnly()) {
         ConnectivityCheck.isConnectedToWifi(ctx)
      } else {
         ConnectivityCheck.isConnected(ctx)
      }
      if (hasNetwork) {
         doSync(ctx)
      }
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
      intentFilter.addAction(SyncWorker.SYNC_UPDATE_BROADCAST)
      mLocalBroadcastManager.registerReceiver(mLocalBroadcastReceiver, intentFilter)
   }

   private fun dismissBroadcastListener() {
      mLocalBroadcastManager.unregisterReceiver(mLocalBroadcastReceiver)
   }

   /**
    * Shows the long-press action menu for a single recipe row, as a
    * centered dialog (like a typical Android long-press context menu)
    * rather than a dropdown anchored to the row -- a plain AlertDialog is
    * centered by default, so no special positioning is needed.
    *
    * "Copy to account" is greyed out (but still visible) when there's no
    * other account to copy into -- AccountManager.getAccountsByType() is
    * a local, in-memory lookup (no network call), so this can run
    * synchronously right here rather than needing a background thread.
    */
   private fun showRecipeContextMenu(recipe: DbRecipePreview) {
      val hasOtherAccount = AccountManager.get(requireContext())
         .getAccountsByType(NEXTCLOUD_ACCOUNT_TYPE).size > 1

      val actions = listOf(
         RecipeContextAction(getString(R.string.copy_to_account_title), hasOtherAccount) {
            openCopyToAccountSheet(recipe)
         },
         RecipeContextAction(getString(R.string.delete_recipe), true, isDestructive = true) {
            confirmDeleteRecipe(recipe)
         }
      )

      val adapter = RecipeContextActionAdapter(requireContext(), actions)
      AlertDialog.Builder(requireContext())
         .setTitle(recipe.name)
         .setAdapter(adapter) { _, which -> actions[which].onSelected() }
         .show()
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

   /**
    * Confirms before deleting -- this removes the recipe from the server,
    * not just the local list, and can't be undone.
    */
   private fun confirmDeleteRecipe(recipe: DbRecipePreview) {
      AlertDialog.Builder(requireContext())
         .setTitle(R.string.delete_recipe_confirm_title)
         .setMessage(getString(R.string.delete_recipe_confirm_message, recipe.name))
         .setPositiveButton(R.string.delete_recipe_confirm_positive) { _, _ -> deleteRecipe(recipe) }
         .setNegativeButton(android.R.string.cancel, null)
         .show()
   }

   /**
    * Same DbRecipePreview -> full DbRecipe lookup as openCopyToAccountSheet,
    * needed here for the recipe's local folder (RecipeDeleter reads its
    * sibling METADATA file to find the recipe's server-side id).
    */
   private fun deleteRecipe(recipe: DbRecipePreview) {
      val repository = DbRecipeRepository.getInstance(requireActivity().application)
      viewLifecycleOwner.lifecycleScope.launch {
         val result = withContext(Dispatchers.IO) {
            val fullRecipe = repository.getRecipeSync(recipe.id)
            val recipeFolder = fullRecipe?.recipeCore?.fileSystem?.filePath?.let { File(it).parentFile }
            if (fullRecipe == null || recipeFolder == null) {
               RecipeDeleter.Result.Failure("Could not find this recipe locally")
            } else {
               RecipeDeleter(requireContext().applicationContext).deleteRecipe(recipeFolder).also { result ->
                  if (result is RecipeDeleter.Result.Success) {
                     repository.deleteRecipe(fullRecipe.recipeCore.name)
                  }
               }
            }
         }

         when (result) {
            is RecipeDeleter.Result.Success ->
               Toast.makeText(requireContext(), getString(R.string.delete_recipe_success, recipe.name), Toast.LENGTH_SHORT).show()
            is RecipeDeleter.Result.Failure ->
               Toast.makeText(requireContext(), getString(R.string.delete_recipe_failure, result.reason), Toast.LENGTH_LONG).show()
         }
      }
   }

   /** A single row in the recipe long-press action dialog. */
   private data class RecipeContextAction(
      val label: String,
      val enabled: Boolean,
      val isDestructive: Boolean = false,
      val onSelected: () -> Unit
   )

   /**
    * Renders each action's label, greying out disabled rows (e.g. "copy"
    * with no other account to copy into) and tinting destructive ones
    * (delete) in the error color -- plain ArrayAdapter/simple_list_item_1
    * doesn't apply either on its own.
    */
   private class RecipeContextActionAdapter(context: Context, private val actions: List<RecipeContextAction>) :
      ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, actions.map { it.label }) {

      override fun areAllItemsEnabled() = false
      override fun isEnabled(position: Int) = actions[position].enabled

      override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
         val view = super.getView(position, convertView, parent) as TextView
         val action = actions[position]
         view.isEnabled = action.enabled
         when {
            !action.enabled -> {
               view.alpha = 0.4f
               view.setTextColor(ContextCompat.getColor(context, R.color.textColor))
            }
            action.isDestructive -> {
               view.alpha = 1f
               view.setTextColor(ContextCompat.getColor(context, R.color.colorError))
            }
            else -> {
               view.alpha = 1f
               view.setTextColor(ContextCompat.getColor(context, R.color.textColor))
            }
         }
         return view
      }
   }
}
