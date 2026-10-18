/*
 * EditRecipeDialogFragment.kt
 */
package com.odiousapps.kat.ui.editrecipe

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentDialog
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.odiousapps.kat.R
import com.odiousapps.kat.databinding.DialogEditRecipeBinding
import com.odiousapps.kat.nextcloudapi.RecipeEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val ARG_RECIPE_JSON_PATH = "recipe_json_path"

/**
 * Full-screen form for editing a recipe's text fields. Starts from the
 * local recipe.json (the raw JSON Cookbook last served for it) and only
 * overwrites the fields shown here, so anything this form doesn't cover
 * (nutrition, image, dates, ...) is sent back to the server unchanged.
 *
 * On a successful save, posts a [REQUEST_KEY] fragment result carrying
 * the old and new names so the host can refresh its list.
 */
class EditRecipeDialogFragment : DialogFragment() {

   private var _binding: DialogEditRecipeBinding? = null
   private val binding get() = _binding!!

   /** The recipe as last loaded from disk -- the base every save is built on. */
   private var original: JSONObject? = null

   /** Form contents right after loading, to tell whether closing would lose edits. */
   private var initialSnapshot: List<String> = emptyList()

   private var saving = false

   companion object {
      const val REQUEST_KEY = "editRecipe"
      const val RESULT_OLD_NAME = "oldName"
      const val RESULT_NEW_NAME = "newName"

      private val DURATION_REGEX =
         Regex("""^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?$""")

      /**
       * @param recipeJsonPath absolute path to the recipe's local recipe.json
       *   (DbRecipe.recipeCore.fileSystem.filePath)
       */
      fun newInstance(recipeJsonPath: String): EditRecipeDialogFragment {
         return EditRecipeDialogFragment().apply {
            arguments = Bundle().apply {
               putString(ARG_RECIPE_JSON_PATH, recipeJsonPath)
            }
         }
      }

      /** ISO 8601 duration as Cookbook stores it (e.g. "PT1H30M0S") -> whole minutes. */
      private fun durationToMinutes(value: String): Int? {
         val match = DURATION_REGEX.find(value.trim()) ?: return null
         val (days, hours, minutes, seconds) = match.destructured
         return (days.toIntOrNull() ?: 0) * 24 * 60 +
            (hours.toIntOrNull() ?: 0) * 60 +
            (minutes.toIntOrNull() ?: 0) +
            ((seconds.toDoubleOrNull() ?: 0.0) / 60).toInt()
      }

      private fun minutesToDuration(minutes: Int) = "PT${minutes / 60}H${minutes % 60}M0S"
   }

   private val recipeJsonFile: File
      get() = File(requireArguments().getString(ARG_RECIPE_JSON_PATH)!!)

   override fun onCreate(savedInstanceState: Bundle?) {
      super.onCreate(savedInstanceState)
      setStyle(STYLE_NORMAL, R.style.AppTheme_FullScreenDialog)
   }

   override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
      _binding = DialogEditRecipeBinding.inflate(inflater, container, false)
      return binding.root
   }

   override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
      super.onViewCreated(view, savedInstanceState)

      // Full-screen dialog windows are drawn edge-to-edge on newer Android
      // versions; keep the toolbar clear of the status bar and the form
      // clear of the keyboard / navigation bar.
      ViewCompat.setOnApplyWindowInsetsListener(binding.editRecipeRoot) { v, insets ->
         val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
         v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
         WindowInsetsCompat.CONSUMED
      }

      binding.editRecipeToolbar.setNavigationOnClickListener { closeIfNoUnsavedChanges() }
      binding.editRecipeToolbar.setOnMenuItemClickListener { item ->
         if (item.itemId == R.id.action_save) {
            save()
            true
         } else {
            false
         }
      }
      (requireDialog() as ComponentDialog).onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
         closeIfNoUnsavedChanges()
      }

      setBusy(true)
      viewLifecycleOwner.lifecycleScope.launch {
         val json = withContext(Dispatchers.IO) {
            try {
               JSONObject(recipeJsonFile.readText())
            } catch (e: Exception) {
               null
            }
         }
         if (json == null) {
            Toast.makeText(requireContext(), R.string.edit_recipe_load_failure, Toast.LENGTH_LONG).show()
            dismiss()
            return@launch
         }
         original = json
         // After a rotation the views have already restored whatever the
         // person had typed -- don't clobber that with the saved recipe.
         if (savedInstanceState == null) {
            populate(json)
         }
         initialSnapshot = snapshotFromJson(json)
         setBusy(false)
      }
   }

   override fun onDestroyView() {
      super.onDestroyView()
      _binding = null
   }

   private fun setBusy(busy: Boolean) {
      binding.editRecipeProgress.visibility = if (busy) View.VISIBLE else View.INVISIBLE
      binding.editRecipeToolbar.menu.findItem(R.id.action_save)?.isEnabled = !busy
      binding.editRecipeScroll.alpha = if (busy) 0.6f else 1f
   }

   // --- JSON <-> form ------------------------------------------------------

   private fun populate(json: JSONObject) {
      binding.editName.setText(json.optString("name"))
      binding.editDescription.setText(json.optString("description"))
      binding.editCategory.setText(json.optString("recipeCategory"))
      binding.editKeywords.setText(json.optString("keywords"))
      binding.editYield.setText(json.optInt("recipeYield", 0).takeIf { it > 0 }?.toString() ?: "")
      binding.editPrepTime.setText(durationToMinutes(json.optString("prepTime"))?.toString() ?: "")
      binding.editCookTime.setText(durationToMinutes(json.optString("cookTime"))?.toString() ?: "")
      binding.editTotalTime.setText(durationToMinutes(json.optString("totalTime"))?.toString() ?: "")
      binding.editUrl.setText(json.optString("url"))
      binding.editTools.setText(stringList(json.opt("tool")).joinToString("\n"))
      binding.editIngredients.setText(stringList(json.opt("recipeIngredient")).joinToString("\n"))
      binding.editInstructions.setText(stringList(json.opt("recipeInstructions")).joinToString("\n"))
   }

   /**
    * Cookbook normally serves these as plain string arrays, but older
    * recipes / imported JSON-LD can have HowToStep-style objects
    * ({"text": ...}) or a single string -- flatten all of them to lines.
    */
   private fun stringList(value: Any?): List<String> = when (value) {
      is JSONArray -> (0 until value.length()).mapNotNull { i ->
         when (val item = value.opt(i)) {
            is JSONObject -> item.optString("text").ifBlank { null }
            null, JSONObject.NULL -> null
            else -> item.toString()
         }
      }
      is String -> value.lines()
      else -> emptyList()
   }.map { it.trim() }.filter { it.isNotEmpty() }

   private fun text(view: android.widget.EditText) = view.text?.toString()?.trim() ?: ""

   private fun lines(view: android.widget.EditText) =
      text(view).lines().map { it.trim() }.filter { it.isNotEmpty() }

   private fun currentSnapshot() = listOf(
      text(binding.editName), text(binding.editDescription), text(binding.editCategory),
      text(binding.editKeywords), text(binding.editYield), text(binding.editPrepTime),
      text(binding.editCookTime), text(binding.editTotalTime), text(binding.editUrl),
      lines(binding.editTools).joinToString("\n"),
      lines(binding.editIngredients).joinToString("\n"),
      lines(binding.editInstructions).joinToString("\n")
   )

   /** Same shape as currentSnapshot(), computed from the JSON instead of the views. */
   private fun snapshotFromJson(json: JSONObject) = listOf(
      json.optString("name").trim(), json.optString("description").trim(),
      json.optString("recipeCategory").trim(), json.optString("keywords").trim(),
      json.optInt("recipeYield", 0).takeIf { it > 0 }?.toString() ?: "",
      durationToMinutes(json.optString("prepTime"))?.toString() ?: "",
      durationToMinutes(json.optString("cookTime"))?.toString() ?: "",
      durationToMinutes(json.optString("totalTime"))?.toString() ?: "",
      json.optString("url").trim(),
      stringList(json.opt("tool")).joinToString("\n"),
      stringList(json.opt("recipeIngredient")).joinToString("\n"),
      stringList(json.opt("recipeInstructions")).joinToString("\n")
   )

   private fun buildEditedJson(base: JSONObject): JSONObject {
      val json = JSONObject(base.toString())
      json.put("name", text(binding.editName))
      json.put("description", text(binding.editDescription))
      json.put("recipeCategory", text(binding.editCategory))
      json.put(
         "keywords",
         text(binding.editKeywords).split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")
      )
      text(binding.editYield).toIntOrNull()?.let { json.put("recipeYield", it) } ?: json.remove("recipeYield")
      putDuration(json, "prepTime", text(binding.editPrepTime))
      putDuration(json, "cookTime", text(binding.editCookTime))
      putDuration(json, "totalTime", text(binding.editTotalTime))
      json.put("url", text(binding.editUrl))
      json.put("tool", JSONArray(lines(binding.editTools)))
      json.put("recipeIngredient", JSONArray(lines(binding.editIngredients)))
      json.put("recipeInstructions", JSONArray(lines(binding.editInstructions)))
      return json
   }

   /** Leaves the original value untouched when the minutes weren't changed, so its exact formatting survives. */
   private fun putDuration(json: JSONObject, key: String, minutesText: String) {
      val minutes = minutesText.toIntOrNull()
      when {
         minutes == null || minutes <= 0 -> json.put(key, "")
         durationToMinutes(json.optString(key)) == minutes -> Unit
         else -> json.put(key, minutesToDuration(minutes))
      }
   }

   // --- actions ------------------------------------------------------------

   private fun closeIfNoUnsavedChanges() {
      if (saving) return
      if (original == null || currentSnapshot() == initialSnapshot) {
         dismiss()
         return
      }
      AlertDialog.Builder(requireContext())
         .setTitle(R.string.edit_recipe_discard_title)
         .setPositiveButton(R.string.edit_recipe_discard_positive) { _, _ -> dismiss() }
         .setNegativeButton(android.R.string.cancel, null)
         .show()
   }

   private fun save() {
      val base = original ?: return
      if (saving) return

      if (text(binding.editName).isEmpty()) {
         binding.editName.error = getString(R.string.edit_recipe_name_required)
         binding.editName.requestFocus()
         return
      }

      // Nothing changed -- no point round-tripping to the server.
      if (currentSnapshot() == initialSnapshot) {
         dismiss()
         return
      }

      val edited = buildEditedJson(base)
      val oldName = base.optString("name")
      val appContext = requireContext().applicationContext
      val jsonFile = recipeJsonFile

      saving = true
      setBusy(true)
      viewLifecycleOwner.lifecycleScope.launch {
         val result = withContext(Dispatchers.IO) { RecipeEditor(appContext).saveRecipe(jsonFile, edited) }
         saving = false
         when (result) {
            is RecipeEditor.Result.Success -> {
               Toast.makeText(appContext, getString(R.string.edit_recipe_success, result.newName), Toast.LENGTH_SHORT).show()
               setFragmentResult(
                  REQUEST_KEY,
                  Bundle().apply {
                     putString(RESULT_OLD_NAME, oldName)
                     putString(RESULT_NEW_NAME, result.newName)
                  }
               )
               dismiss()
            }
            is RecipeEditor.Result.Failure -> {
               setBusy(false)
               Toast.makeText(appContext, getString(R.string.edit_recipe_failure, result.reason), Toast.LENGTH_LONG).show()
            }
         }
      }
   }
}
