/*
 * SyncProgressDialog.kt
 *
 * Copyright 2026 by MicMun
 */
package com.odiousapps.kat.ui.sync

import android.annotation.SuppressLint
import android.app.Dialog
import android.os.Bundle
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.odiousapps.kat.R
import com.odiousapps.kat.services.sync.SyncScheduler
import com.odiousapps.kat.services.sync.SyncWorker

/**
 * Shows live "X of Y - Recipe Name" progress for a manually-triggered
 * sync (the initial sync right after logging in, or a pull-to-refresh),
 * reading the same progress data [SyncWorker] publishes via
 * setProgress() for any observer of [SyncScheduler.observeManualSyncState].
 * Not cancelable -- the sync itself keeps running regardless (it's
 * WorkManager-backed, not tied to this dialog's lifetime), so dismissing
 * it early would just hide progress without actually stopping anything,
 * which would be more confusing than helpful.
 *
 * For periodic/background syncs, see SyncNotificationHelper instead --
 * nothing is necessarily watching a dialog while one of those runs.
 */
class SyncProgressDialog : DialogFragment() {

   override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
      isCancelable = false

      @SuppressLint("InflateParams") // dialog content views are never attached to a parent at inflation time
      val view = layoutInflater.inflate(R.layout.dialog_sync_progress, null)
      val progressBar = view.findViewById<ProgressBar>(R.id.syncProgressBar)
      val progressText = view.findViewById<TextView>(R.id.syncProgressText)
      progressText.text = getString(R.string.syncing_recipes)

      val dialog = AlertDialog.Builder(requireContext())
         .setTitle(R.string.sync_notification_title)
         .setView(view)
         .create()

      // `this` (the Fragment's own lifecycle), not viewLifecycleOwner --
      // onCreateDialog runs before a DialogFragment necessarily has a
      // regular fragment view set up.
      SyncScheduler.observeManualSyncState(requireContext()).observe(this) { workInfos ->
         val active = workInfos.firstOrNull { !it.state.isFinished }
         if (active == null) {
            // Finished (or nothing running at all, e.g. this dialog
            // somehow outlived the sync it was showing) -- either way,
            // nothing left to show progress for.
            dismissAllowingStateLoss()
            return@observe
         }

         val item = active.progress.getInt(SyncWorker.PROGRESS_ITEM, 0)
         val overall = active.progress.getInt(SyncWorker.PROGRESS_OVERALL, 0)
         val title = active.progress.getString(SyncWorker.PROGRESS_TITLE) ?: ""

         if (overall > 0) {
            // Real per-item progress has started arriving -- switch from
            // the initial indeterminate spinner (shown while still just
            // fetching the recipe list from the server, before
            // individual downloads begin) to a determinate bar.
            progressBar.isIndeterminate = false
            progressBar.max = overall
            progressBar.progress = item
            progressText.text = getString(R.string.sync_notification_progress, item, overall, title)
         }
      }

      return dialog
   }

   companion object {
      private const val TAG = "SyncProgressDialog"

      /** Shows the dialog, unless one's already showing -- avoids a duplicate if this gets called more than once for the same sync. */
      fun showIfNotShown(fragmentManager: FragmentManager) {
         if (fragmentManager.findFragmentByTag(TAG) == null) {
            SyncProgressDialog().show(fragmentManager, TAG)
         }
      }
   }
}
