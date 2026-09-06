/*
 * RecipeImportLoginActivity.kt
 *
 * Copyright 2026 by MicMun
 */
package com.odiousapps.kat.ui.recipeimport

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.odiousapps.kat.R
import com.odiousapps.kat.databinding.ActivityRecipeImportLoginBinding
import com.odiousapps.kat.nextcloudapi.NextcloudLoginFlow
import com.odiousapps.kat.nextcloudapi.RecipeImportCredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shows Nextcloud's own Login Flow v2 page in a WebView -- the same
 * mechanism, and the same idea of showing it in an in-app "mini browser",
 * that the official Nextcloud Android app itself uses -- so the user can
 * authenticate and grant this app's recipe-import feature its own,
 * independently-revocable app password without this app (or the
 * server-side bridge script it talks to) ever seeing the account's real
 * password.
 *
 * On success, stores the result via [RecipeImportCredentialStore] and
 * finishes with [android.app.Activity.RESULT_OK]; on cancellation,
 * failure, or timeout, finishes with RESULT_CANCELED. Callers should
 * launch this via [registerForActivityResult] and, on success, re-read
 * the credential from [RecipeImportCredentialStore] rather than expecting
 * it back via Intent extras -- it never leaves encrypted storage once
 * saved.
 */
class RecipeImportLoginActivity : AppCompatActivity() {

   private lateinit var binding: ActivityRecipeImportLoginBinding
   private var pollJob: Job? = null

   companion object {
      const val EXTRA_HOSTNAME = "hostname"
      const val EXTRA_ACCOUNT_NAME = "account_name"

      // Nextcloud's own poll token expires after 20 minutes server-side;
      // stop polling a little before that so this always ends with a
      // clear "timed out" message instead of just running until the
      // server starts rejecting it anyway.
      private const val TIMEOUT_MS = 19 * 60 * 1000L
      private const val POLL_INTERVAL_MS = 1500L

      fun newIntent(context: Context, hostname: String, accountName: String): Intent {
         return Intent(context, RecipeImportLoginActivity::class.java).apply {
            putExtra(EXTRA_HOSTNAME, hostname)
            putExtra(EXTRA_ACCOUNT_NAME, accountName)
         }
      }
   }

   override fun onCreate(savedInstanceState: Bundle?) {
      super.onCreate(savedInstanceState)
      binding = ActivityRecipeImportLoginBinding.inflate(layoutInflater)
      setContentView(binding.root)

      val hostname = intent.getStringExtra(EXTRA_HOSTNAME)
      val accountName = intent.getStringExtra(EXTRA_ACCOUNT_NAME)
      if (hostname.isNullOrEmpty() || accountName.isNullOrEmpty()) {
         setResult(RESULT_CANCELED)
         finish()
         return
      }

      // Mini-browser back behavior, matching the official Nextcloud app:
      // step back through the login/2FA flow's own page history first,
      // only treat back-press as "cancel the whole thing" once there's
      // nowhere left in the WebView to go back to.
      onBackPressedDispatcher.addCallback(this) {
         if (binding.webView.canGoBack()) {
            binding.webView.goBack()
         } else {
            setResult(RESULT_CANCELED)
            finish()
         }
      }

      binding.closeButton.setOnClickListener {
         setResult(RESULT_CANCELED)
         finish()
      }

      setupWebView()
      startLoginFlow(hostname, accountName)
   }

   private fun setupWebView() {
      // JavaScript/DOM storage are needed here because this WebView's one
      // and only job is rendering Nextcloud's own hosted login page,
      // which needs both -- same as the official Nextcloud app's own
      // login WebView. This isn't a general-purpose in-app browser for
      // arbitrary sites, which would be a very different risk profile.
      binding.webView.settings.javaScriptEnabled = true
      binding.webView.settings.domStorageEnabled = true

      binding.webView.webViewClient = object : WebViewClient() {
         // Deliberately no overrides here beyond onPageFinished: the
         // default WebViewClient already does the right thing for both
         // navigation (stays inside this WebView, including any 2FA/SSO
         // redirects to a different host -- needed for real-world
         // Nextcloud logins that go through a separate identity
         // provider) and SSL errors (fails closed). A WebViewClient that
         // overrides onReceivedSslError to always proceed is a common
         // mistake and a real vulnerability -- not done here.
         override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            binding.loadingIndicator.visibility = View.GONE
            binding.webView.visibility = View.VISIBLE
         }
      }
   }

   private fun startLoginFlow(hostname: String, accountName: String) {
      lifecycleScope.launch {
         val initResult = try {
            withContext(Dispatchers.IO) { NextcloudLoginFlow.initiate(hostname) }
         } catch (e: Exception) {
            Toast.makeText(
               this@RecipeImportLoginActivity,
               getString(R.string.recipe_import_login_init_failed, e.message ?: ""),
               Toast.LENGTH_LONG
            ).show()
            setResult(RESULT_CANCELED)
            finish()
            return@launch
         }

         binding.webView.loadUrl(initResult.loginUrl)
         pollForCredentials(initResult, accountName)
      }
   }

   private fun pollForCredentials(initResult: NextcloudLoginFlow.InitResult, accountName: String) {
      pollJob = lifecycleScope.launch {
         val deadline = System.currentTimeMillis() + TIMEOUT_MS
         while (System.currentTimeMillis() < deadline) {
            val credentials = withContext(Dispatchers.IO) {
               try {
                  NextcloudLoginFlow.poll(initResult.pollEndpoint, initResult.pollToken)
               } catch (_: Exception) {
                  // Transient network hiccup -- the token is still good
                  // for the full 20 minutes regardless, so just try
                  // again next tick rather than giving up on one failed
                  // request.
                  null
               }
            }

            if (credentials != null) {
               RecipeImportCredentialStore.save(
                  this@RecipeImportLoginActivity,
                  accountName,
                  RecipeImportCredentialStore.Credentials(
                     server = credentials.server,
                     loginName = credentials.loginName,
                     appPassword = credentials.appPassword,
                  )
               )
               setResult(RESULT_OK)
               finish()
               return@launch
            }

            delay(POLL_INTERVAL_MS)
         }

         Toast.makeText(this@RecipeImportLoginActivity, R.string.recipe_import_login_timed_out, Toast.LENGTH_LONG).show()
         setResult(RESULT_CANCELED)
         finish()
      }
   }

   override fun onDestroy() {
      pollJob?.cancel()
      super.onDestroy()
   }
}
