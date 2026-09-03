package com.odiousapps.kat.ui.recipedetail

import android.Manifest
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.viewpager2.widget.ViewPager2
import com.fondesa.kpermissions.*
import com.fondesa.kpermissions.extension.permissionsBuilder
import com.fondesa.kpermissions.request.PermissionRequest
import com.google.android.material.tabs.TabLayoutMediator
import com.odiousapps.kat.MainApplication
import com.odiousapps.kat.R
import com.odiousapps.kat.databinding.FragmentDetailBinding
import com.odiousapps.kat.db.model.DbRecipe
import com.odiousapps.kat.services.CooktimerService
import com.odiousapps.kat.services.RemainReceiver
import com.odiousapps.kat.settings.PreferenceData
import com.odiousapps.kat.ui.*
import com.odiousapps.kat.ui.widget.BlinkAnimation
import com.odiousapps.kat.util.DurationUtils
import com.odiousapps.kat.util.ManagedAlarmPlayer
import com.odiousapps.kat.util.ManagedVibrator
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import androidx.core.view.isVisible
import androidx.core.view.isGone

/**
 * Fragment for detail of a recipe.
 *
 * @author MicMun
 * @version 2.3, 05.03.23
 */
class RecipeDetailFragment : Fragment(), CookTimeClickListener, TimerClickListener {
   private lateinit var binding: FragmentDetailBinding
   private lateinit var viewModel: RecipeViewModel
   private lateinit var settingViewModel: CurrentSettingViewModel

   private var adapter: ViewPagerAdapter? = null

   private var currentPage = 0

   private var recipeId = -1L

   private var currentRecipe: DbRecipe? = null

   // Cooking timer
   var currentRemains: Long = -1L
   private val animation = BlinkAnimation()
   private lateinit var alarmPlayer: ManagedAlarmPlayer
   private lateinit var vibrator: ManagedVibrator
   private var request: PermissionRequest? = null

   companion object {
      private const val KEY_CURRENT_PAGE = "current_page"
   }

   override fun onCreate(savedInstanceState: Bundle?) {
      super.onCreate(savedInstanceState)
      val args = RecipeDetailFragmentArgs.fromBundle(requireArguments())
      if (savedInstanceState != null) {
         @Suppress("DEPRECATION")
         currentPage = savedInstanceState[KEY_CURRENT_PAGE] as Int
      }
      recipeId = args.recipeId
   }

   override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
      binding = DataBindingUtil.inflate(inflater, R.layout.fragment_detail, container, false)
      binding.timerClickListener = this
      // initialize alarm and vibration
      alarmPlayer = ManagedAlarmPlayer(requireContext())
      vibrator = ManagedVibrator(requireContext())

      // Settings view model
      val factory = CurrentSettingViewModelFactory(MainApplication.AppContext)
      settingViewModel =
         ViewModelProvider(MainApplication.AppContext, factory)[CurrentSettingViewModel::class.java]

      val viewModelFactory = RecipeViewModelFactory(recipeId, requireActivity().application)
      viewModel = ViewModelProvider(this, viewModelFactory)[RecipeViewModel::class.java]
      binding.lifecycleOwner = viewLifecycleOwner

      val parent = (activity as MainActivity?)!!

      // request for notification permission (since Android P = API 28)
      request = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
         permissionsBuilder(
              Manifest.permission.FOREGROUND_SERVICE,
              Manifest.permission.POST_NOTIFICATIONS
         ).build()
      } else
         permissionsBuilder(
              Manifest.permission.FOREGROUND_SERVICE
         ).build()

      parent.showToolbar(false)

      // This screen hides MainActivity's shared app bar (above), so its
      // own top-of-screen content (cookTimerLayout, and everything in
      // linearLayout3) needs its own status bar inset handling -- without
      // it, that content renders underneath the status bar.
      ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
         val statusBarInsets = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
         view.updatePadding(top = statusBarInsets.top)
         windowInsets
      }

      keepScreenAlive()

      return binding.root
   }

   override fun onStop() {
      (activity as MainActivity?)!!.showToolbar(true)
      allowScreenSleep()
      super.onStop()
   }

   /**
    * Initialise the view pager and tablayout with the current recipe.
    *
    * @param recipe current recipe data.
    */
   private fun initPager(recipe: DbRecipe, orientation: Int) {
      adapter = ViewPagerAdapter(recipe, orientation, this)
      binding.pager.adapter = adapter
      binding.pager.offscreenPageLimit = adapter!!.itemCount
      val tabLayout = binding.tabLayout
      TabLayoutMediator(tabLayout, binding.pager) { tab, position ->
         when (adapter!!.getItemViewType(position)) {
            ViewPagerAdapter.TYPE_INFO -> {
               tab.icon = AppCompatResources.getDrawable(this.requireContext(), R.drawable.ic_info)
            }
            ViewPagerAdapter.TYPE_INGREDIENTS -> {
               tab.icon = AppCompatResources.getDrawable(this.requireContext(), R.drawable.ic_ingredients)
            }
            ViewPagerAdapter.TYPE_INSTRUCTIONS -> {
               tab.icon = AppCompatResources.getDrawable(this.requireContext(), R.drawable.ic_instructions)
            }
            ViewPagerAdapter.TYPE_NUTRITIONS -> {
               tab.icon = AppCompatResources.getDrawable(this.requireContext(), R.drawable.ic_nutritions)
            }
            ViewPagerAdapter.TYPE_INGRED_AND_INSTRUCT -> {
               tab.icon = AppCompatResources.getDrawable(this.requireContext(), R.drawable.ic_instructions)
            }
         }
      }.attach()

      binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
         override fun onPageSelected(position: Int) {
            currentPage = position

            if (position == ViewPagerAdapter.TYPE_INFO) {
               updateCooktimerViewsVisibility(true)
            } else {
               updateCooktimerViewsVisibility(false)
            }
            super.onPageSelected(position)
         }
      })
      binding.viewCooktimerPlaceholder.visibility = View.GONE

      if (currentPage != ViewPagerAdapter.TYPE_INFO) {
         binding.pager.setCurrentItem(currentPage, false)
      }
   }

   override fun onSaveInstanceState(outState: Bundle) {
      outState.putInt(KEY_CURRENT_PAGE, currentPage)
      outState.putBoolean("ALARM_PLAYING", alarmPlayer.isPlaying)
      outState.putBoolean("VIBRATOR_VIBRATING", vibrator.isVibrating)
      alarmPlayer.stop()
      vibrator.stop()
      super.onSaveInstanceState(outState)
   }

   override fun onPause() {
      super.onPause()
      allowScreenSleep()
      if (viewModel.state.value == RecipeViewModel.CooktimeState.RUNNING) {
         startService(viewModel.currentMillis.value!!)
      }
   }

   override fun onResume() {
      super.onResume()

      keepScreenAlive()
      (activity as MainActivity?)!!.showToolbar(false)

      if (adapter != null) {
         val type = adapter!!.getItemViewType(currentPage)
         if (type == ViewPagerAdapter.TYPE_INSTRUCTIONS || type == ViewPagerAdapter.TYPE_INGRED_AND_INSTRUCT) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
         }
      }

      val orientation = resources.configuration.orientation

      viewModel.recipe.observe(viewLifecycleOwner) { recipe ->
         recipe?.let {
            initPager(it, orientation)
            setTitle(it.recipeCore.name)
            currentRecipe = recipe

            // if service started, take value and stop service
            if (MainApplication.AppContext.receiver != null) {
               currentRemains = MainApplication.AppContext.receiver!!.remains ?: -1
               stopService()

               // cook timer
               viewModel.total = DurationUtils.durationInSeconds(recipe.recipeCore.cookTime) * 1000
               viewModel.setCurrentMillis(currentRemains)

               if (currentRemains > 0L) {
                  startTimer()
               } else {
                  stopTimer()
               }
               showCooktimer()
               handleState()
            }
         }
      }
   }

   @Deprecated("Deprecated in Java")
   @Suppress("DEPRECATION")
   override fun onActivityCreated(savedInstanceState: Bundle?) {
      super.onActivityCreated(savedInstanceState)
      val title = viewModel.recipe.value?.recipeCore?.name
      title?.let {
         setTitle(it)
      }
      if (savedInstanceState?.getBoolean("ALARM_PLAYING") == true) playAlarm()
      if (savedInstanceState?.getBoolean("VIBRATOR_VIBRATING") == true) vibrate()
   }

   private fun updateCooktimerViewsVisibility(isDetails: Boolean) {
      if (binding.cookTimerLayout.isVisible && !isDetails) {
         binding.viewCooktimerPlaceholder.visibility = View.VISIBLE
      } else {
         binding.viewCooktimerPlaceholder.visibility = View.GONE
      }
   }

   private fun hideCooktimer() {
      binding.cookTimerLayout.visibility = LinearLayout.GONE
      // isDetails does not matter when cooktimer is not visible
      updateCooktimerViewsVisibility(true)
   }

   private fun showCooktimer() {
      binding.cookTimerLayout.visibility = LinearLayout.VISIBLE
      // isDetails is true, because only DetailView can start timer
      updateCooktimerViewsVisibility(true)
   }

   private fun setTitle(title: String) {
      (activity as AppCompatActivity).supportActionBar?.title = title
   }

   override fun onClick(recipe: DbRecipe) {
      if (binding.cookTimerLayout.isGone) {
         if (recipe.recipeCore.cookTime.isNotEmpty()) {
            showCooktimer()
            initTimer(recipe.recipeCore.cookTime)
         } else {
            Toast.makeText(requireContext(), getString(R.string.recipe_no_timer), Toast.LENGTH_SHORT).show()
         }
      } else {
         cancelTimer()
      }
   }

   private fun keepScreenAlive() {
      if (PreferenceData.getInstance().getScreenKeepalive()) {
         activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
      }
   }

   private fun allowScreenSleep() {
      if (PreferenceData.getInstance().getScreenKeepalive()) {
         activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
      }
   }

   private fun initTimer(cookTime: String) {
      viewModel.total = DurationUtils.durationInSeconds(cookTime) * 1000
      Log.d("RecipeDetailFragment", "Timer value: ${viewModel.total}")
      handleState()
   }

   override fun onTimerClicked(view: View) {
      when (view.tag) {
         "Start" -> startTimer()
         "Pause" -> pauseTimer()
         "Stop" -> stopTimer()
         else -> cancelTimer()
      }
   }

   private fun startTimer() {
      if (currentRemains != -1L)
         viewModel.setCurrentMillis(currentRemains)
      viewModel.startTimer()
   }

   private fun pauseTimer() {
      viewModel.stopTimer()
   }

   private fun stopTimer() {
      viewModel.stopTimer()
      viewModel.resetTimer()
      binding.countTimerTxt.text = DurationUtils.formatDurationSeconds(viewModel.total!! / 1000)
   }

   private fun handleState() {
      viewModel.state.observe(viewLifecycleOwner) { state ->
         when (state) {
            RecipeViewModel.CooktimeState.NOT_STARTED -> { // not started
               // animations
               blinkText(false)
               binding.countTimerTxt.clearAnimation()
               if (alarmPlayer.isPlaying)
                  alarmPlayer.stop()
               if (vibrator.isVibrating)
                  vibrator.stop()
               binding.timerStartBtn.setImageResource(R.drawable.ic_start)
               binding.timerStartBtn.tag = "Start"
               currentRemains = viewModel.total ?: -1L
               refreshUI(currentRemains)
            }
            RecipeViewModel.CooktimeState.PAUSED -> { // paused
               // animations
               currentRemains = viewModel.currentMillis.value!!
               refreshUI(currentRemains)
               blinkText(true)
               binding.timerStartBtn.setImageResource(R.drawable.ic_start)
               binding.timerStartBtn.tag = "Start"
            }
            RecipeViewModel.CooktimeState.FINISHED -> { // timer finished (no remaining time)
               // animations
               currentRemains = viewModel.total!!
               refreshUI(currentRemains)
               blinkText(true)
               // alarms
               playAlarm()
               vibrate()
               // stop timer after two seconds
               Handler(Looper.getMainLooper()).postDelayed({
                  viewModel.stopTimer()
                  viewModel.resetTimer()
               }, 5000)
            }
            else -> {// running
               currentRemains = viewModel.currentMillis.value!!
               refreshUI(currentRemains)
               // animations
               blinkText(false)
               binding.timerStartBtn.setImageResource(R.drawable.ic_pause)
               binding.timerStartBtn.tag = "Pause"
            }
         }
      }
   }

   private fun refreshUI(remains: Long) {
      binding.countTimerTxt.text = DurationUtils.formatDurationSeconds(remains / 1000)
   }

   private fun cancelTimer() {
      val state = viewModel.state.value
      if (state != null &&
         (state == RecipeViewModel.CooktimeState.RUNNING ||
               state == RecipeViewModel.CooktimeState.PAUSED)
      ) {
         stopTimer()
      }
      hideCooktimer()
   }

   private fun blinkText(enabled: Boolean) {
      if (enabled && binding.countTimerTxt.animation == null) {
         binding.countTimerTxt.startAnimation(animation)
      } else {
         binding.countTimerTxt.clearAnimation()
      }
   }

   /**
    * Play the alarm.
    */
   private fun playAlarm() {
      alarmPlayer.play(R.raw.timer_expire_short)
      Executors.newSingleThreadScheduledExecutor().schedule({ alarmPlayer.stop() }, 20, TimeUnit.SECONDS)
   }

   /**
    * Start vibration.
    */
   private fun vibrate() {
      val vibratorPattern = longArrayOf(0, 100, 100, 200, 500)
      vibrator.vibrate(vibratorPattern)
      Executors.newSingleThreadScheduledExecutor().schedule({ vibrator.stop() }, 10, TimeUnit.SECONDS)
   }

   private fun startService(remains: Long) {
      if (checkServicePermission()) {
         Log.d("RecipeDetailFragment", "startService: remains = $remains")
         MainApplication.AppContext.receiver = RemainReceiver()
         LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(MainApplication.AppContext.receiver!!, IntentFilter(RemainReceiver.REMAIN_ACTION))
         val intent = cooktimeService()
         intent.putExtra("RECIPE_ID", currentRecipe!!.recipeCore.id)
         intent.putExtra("COOK_TIME", remains)
         requireActivity().startService(intent)
      }
   }

   private fun stopService() {
      Log.d("RecipeDetailFragment", "stopService: remains = $currentRemains")
      requireActivity().stopService(cooktimeService())
      MainApplication.AppContext.receiver?.let {
         LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(it)
         MainApplication.AppContext.receiver = null
         Log.d("RecipeDetailFragment", "stopService: receiver = ${MainApplication.AppContext.receiver}")
      }
   }

   /**
    * Returns the intent for the countdown service.
    *
    * @return the intent for the countdown service.
    */
   private fun cooktimeService() = Intent(activity, CooktimerService::class.java)

   /**
    * Returns <code>true</code>, if the app has the permission to start a foreground service.
    *
    * @return <code>true</code>, if the app has the permission to start a foreground service.
    */
   private fun checkServicePermission(): Boolean {
      var result = true

      if (request != null) { // api >= 28
         val permissions = request!!.checkStatus()
         if (permissions.allGranted()) // if permission is already granted
            result = true
         else {
            result = false
            val context = requireContext()
            request!!.addListener { perms ->
               when {
                  perms.anyPermanentlyDenied() ->
                     context.showPermanentlyDeniedDialog(getString(R.string.permission_notification_required))
                  perms.anyShouldShowRationale() -> context.showRationaleDialog(perms, request!!)
                  perms.allGranted() -> {
                     context.showGrantedToast(perms)
                     result = true
                  }
               }
            }
            request!!.send()
         }
      }

      return result
   }
}
