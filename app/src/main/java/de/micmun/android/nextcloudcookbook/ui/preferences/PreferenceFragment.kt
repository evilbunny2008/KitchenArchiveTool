/*
 * PreferenceFragment.kt.kt
 *
 * Copyright 2020 by MicMun
 */
package de.micmun.android.nextcloudcookbook.ui.preferences

import android.app.TaskStackBuilder
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.anggrayudi.storage.file.getAbsolutePath
import com.google.android.material.snackbar.Snackbar
import de.micmun.android.nextcloudcookbook.R
import de.micmun.android.nextcloudcookbook.ui.MainActivity
import de.micmun.android.nextcloudcookbook.util.StorageManager
import kotlinx.coroutines.flow.collect

/**
 * Fragment for settings.
 *
 * @author MicMun
 * @version 2.0, 15.04.22
 */
class PreferenceFragment : PreferenceFragmentCompat(), Preference.OnPreferenceChangeListener,
                           Preference.OnPreferenceClickListener {
   private lateinit var viewModel: PreferenceViewModel

   private lateinit var dirPreference: Preference
   private lateinit var themePreference: IntListPreference
   private lateinit var syncPreference: IntListPreference
   private lateinit var wifiOnlyPreference: Preference

   private val getContent = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
      it?.let { uri ->
         if (Uri.decode(uri.toString()).endsWith(":")) {
            Snackbar.make(requireView(), "Cannot use root folder!", Snackbar.LENGTH_SHORT).show()
         } else {
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            requireContext().contentResolver.takePersistableUriPermission(uri, takeFlags)
            viewModel.setRecipeDirectory(uri.toString())
            viewModel.setInitialized(true)
            viewModel.setStorageAccessed(true)
         }
      }
   }
   private var currentDirectory: String = ""

   @Deprecated("Deprecated in Java")
   override fun onActivityCreated(savedInstanceState: Bundle?) {
      @Suppress("DEPRECATION")
      super.onActivityCreated(savedInstanceState)
      (activity as AppCompatActivity).supportActionBar?.title = getString(R.string.menu_settings_title)
   }

   override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
      setPreferencesFromResource(R.xml.preferences, rootKey)
      viewModel = ViewModelProvider(this).get(PreferenceViewModel::class.java)

      // find prefs
      dirPreference = findPreference(getString(R.string.prefkey_recipeDir))!!
      themePreference = findPreference(getString(R.string.prefkey_theme))!!
      syncPreference = findPreference(getString(R.string.prefkey_enableSyncService))!!
      wifiOnlyPreference = findPreference(getString(R.string.prefkey_sync_wifi_only))!!
      themePreference.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
      val aboutPreference: Preference = findPreference(getString(R.string.prefkey_about))!!

      // change listener
      dirPreference.onPreferenceChangeListener = this
      themePreference.onPreferenceChangeListener = this
      syncPreference.onPreferenceChangeListener = this
      wifiOnlyPreference.onPreferenceChangeListener = this

      // click listener
      dirPreference.onPreferenceClickListener = this

      // about version
      val version = requireContext().packageManager.getPackageInfo(requireActivity().packageName, 0).versionName ?: ""
      aboutPreference.title = getString(R.string.about_version, version)
   }

   override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
      // observe values
      lifecycleScope.launchWhenResumed {
         viewModel.recipeDirectory.collect { dir ->
            val summary = if (dir.isEmpty()) "" else StorageManager.getDocumentFromString(requireContext(), dir)
                                                        ?.getAbsolutePath(requireContext()) ?: ""
            dirPreference.summary = summary
            currentDirectory = dir
         }
         viewModel.theme.collect { theme ->
            themePreference.value = theme.toString()
            themePreference.summary = themePreference.entry
         }
      }

      (activity as MainActivity?)?.showToolbar(
         showToolbar = true,
         showSearch = false,
         showSort = false
      )

      return super.onCreateView(inflater, container, savedInstanceState)
   }

   override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
      when (preference) {
         dirPreference -> viewModel.setRecipeDirectory(newValue.toString())
         syncPreference -> viewModel.setSyncServiceInterval(newValue.toString().toInt())
         wifiOnlyPreference -> viewModel.setWifiOnly(newValue.toString().toBoolean())
         themePreference -> {
            viewModel.setTheme(newValue.toString().toInt())
            // recreate activity
            TaskStackBuilder.create(context)
               .addNextIntent(Intent(activity, MainActivity::class.java))
               .addNextIntent(requireActivity().intent)
               .startActivities()
         }
         else -> return false
      }
      return true
   }

   override fun onPreferenceClick(preference: Preference): Boolean {
      when (preference) {
         dirPreference -> {
            chooseFolder()
         }
         else -> return false
      }
      return true
   }

   /**
    * Choose dialog for picking a folder.
    */
   private fun chooseFolder() {
      askPermission()
   }

   private fun askPermission() {
      val folder = StorageManager.getDocumentFromString(requireContext(), currentDirectory)
      getContent.launch(folder?.uri ?: Uri.EMPTY)
   }
}
