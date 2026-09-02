/*
 * SearchFormFragment.kt
 *
 * Copyright 2020 by MicMun
 */
package com.odiousapps.nextcloudcookbook.ui.downloadform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.scale
import androidx.databinding.DataBindingUtil
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.anggrayudi.storage.file.findFolder
import com.anggrayudi.storage.file.openOutputStream
import com.odiousapps.nextcloudcookbook.MainApplication
import com.odiousapps.nextcloudcookbook.R
import com.odiousapps.nextcloudcookbook.databinding.FragmentDownloadFormBinding
import com.odiousapps.nextcloudcookbook.json.model.Recipe
import com.odiousapps.nextcloudcookbook.nextcloudapi.Sync
import com.odiousapps.nextcloudcookbook.ui.CurrentSettingViewModel
import com.odiousapps.nextcloudcookbook.ui.CurrentSettingViewModelFactory
import com.odiousapps.nextcloudcookbook.ui.MainActivity
import com.odiousapps.nextcloudcookbook.util.StorageManager
import com.odiousapps.nextcloudcookbook.util.json.RecipeJsonConverter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.MalformedURLException
import java.net.URL

/**
 * Fragment for recipe download form.
 *
 * @author Leafar
 * @version 1.3, 27.11.21
 */
class DownloadFormFragment : Fragment(), DownloadClickListener {
   private lateinit var binding: FragmentDownloadFormBinding
   private lateinit var settingViewModel: CurrentSettingViewModel

   private var fragmentJob = Job()
   private val uiScope = CoroutineScope(Dispatchers.Main + fragmentJob)

   private var recipeDir: String? = null
   private val isDownloading = MutableLiveData(false)

   override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
      binding = DataBindingUtil.inflate(inflater, R.layout.fragment_download_form, container, false)
      binding.clickListener = this

      val factory = CurrentSettingViewModelFactory(MainApplication.AppContext)
      settingViewModel = ViewModelProvider(MainApplication.AppContext, factory)[CurrentSettingViewModel::class.java]

      lifecycleScope.launch {
         collectRecipeDir()
      }

      isDownloading.observe(viewLifecycleOwner) { isDownloading ->
         binding.downloadBtn.isEnabled = !isDownloading
      }

      (activity as MainActivity?)?.showToolbar(
         showToolbar = true,
         showSearch = false,
         showSort = false
      )

      return binding.root
   }

   private suspend fun collectRecipeDir() {
      viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.CREATED) {
         settingViewModel.recipeDirectory.collect { dir ->
            recipeDir = dir
         }
      }
   }

   @Deprecated("Deprecated in Java")
   override fun onActivityCreated(savedInstanceState: Bundle?) {
      @Suppress("DEPRECATION")
      super.onActivityCreated(savedInstanceState)
      (requireActivity() as AppCompatActivity).supportActionBar?.title =
         resources.getString(R.string.form_download_title)
   }

   override fun doDownload() {
      val url = binding.recipeUrlTxt.text.toString()
      val overridePath = binding.recipeOverridePath.text.toString()
      val replaceExisting = binding.replaceExistingChkBox.isChecked

      isDownloading.postValue(true)
      uiScope.launch {
         fetchAndParse(url)?.let { pair ->
            val recipe = pair.first
            if (recipe.name.isNotEmpty()) {
               val storage =
                  StorageManager.getDocumentFromString(requireContext(), this@DownloadFormFragment.recipeDir ?: "")
               if (storage?.exists() == true) {
                  val recipeDirName = overridePath.ifEmpty {
                     recipe.name
                  }
                  var recipeDir = storage.findFolder(recipeDirName)
                  if (recipeDir == null || replaceExisting) {
                     if (recipeDir == null)
                        recipeDir = storage.createDirectory(recipeDirName)
                     recipeDir?.findOrCreateFile(Sync.NEW_FILE_MARKER)
                     val recipeFile = recipeDir?.findOrCreateFile(Sync.RECIPE)
                     val writer = recipeFile?.openOutputStream(requireContext(), false)?.bufferedWriter()
                     // we write the full json to also keep fields we do not process yet
                     writer?.write(pair.second.toString())
                     writer?.close()

                     if (recipe.image?.isNotBlank() == true) {
                        val bm = fetchImage(recipe.image!!)
                        if (bm != null) {
                           saveAsJpeg(bm, recipeDir, "full.jpg")

                           // crop image to a square
                           val minExtent = bm.width.coerceAtMost(bm.height)
                           val cutoff = Pair(bm.width - minExtent, bm.height - minExtent)
                           val croppedBm =
                              Bitmap.createBitmap(bm, cutoff.first / 2, cutoff.second / 2, minExtent, minExtent)

                           val thumbnail = croppedBm.scale(144, 144)
                           saveAsJpeg(thumbnail, recipeDir, "thumb.jpg")
                           val thumbnail16 = croppedBm.scale(16, 16)
                           saveAsJpeg(thumbnail16, recipeDir, "thumb16.jpg")
                        }
                     }
                  } else {
                     downloadError("Directory '${recipeDirName}' already exists")
                  }
               } else {
                  downloadError("No recipe directory found. Check the settings")
               }
            } else {
               downloadError("Parsed recipe has no name")
            }
         }
         isDownloading.postValue(false)
      }
   }

   private fun sanitizeURL(str: String): String {
      // TODO should we enable cleartext traffic (http)?
      var url: URL
      url = try {
         URL(str)
      } catch (_: MalformedURLException) {
         try {
            URL("https://$str")
         } catch (_: MalformedURLException) {
            return str
         }
      }
      if (url.protocol != "https")
         url = URL("https", url.host, url.file)
      return url.toString()
   }

   private suspend fun fetchAndParse(url: String): Pair<Recipe, JsonObject>? {
      return withContext(Dispatchers.IO) {
         val document: Document
         try {
            document = Jsoup.connect(sanitizeURL(url)).get()
         } catch (_: MalformedURLException) {
            downloadError("Malformed URL")
            return@withContext null
         } catch (e: HttpStatusException) {
            downloadError("Http Error ${e.statusCode}")
            return@withContext null
         } catch (_: Exception) {
            downloadError("Connection failed")
            return@withContext null
         }
         for (element in document.getElementsByTag("script")) {
            if (element.attr("type") == "application/ld+json") {
               val json = element.html()
               try {
                  RecipeJsonConverter.parseFromWeb(json)?.let { jsonObj ->
                     // we may need to patch the source url into the json data
                     if (!jsonObj.containsKey("url")) {
                        val map = jsonObj.toMutableMap()
                        map["url"] = JsonPrimitive(url)
                        val newObj = JsonObject(map)
                        RecipeJsonConverter.parse(newObj)?.let { recipe ->
                           return@withContext Pair(recipe, newObj)
                        }
                     }
                  }
               } catch (e: SerializationException) {
                  Log.e("JSON", "Parsing error", e)
               }
            }
         }
         downloadError("No parsable recipe found")
         null
      }
   }

   private suspend fun fetchImage(url: String): Bitmap? {
      return withContext(Dispatchers.IO) {
         try {
            val stream = URL(sanitizeURL(url)).openStream()
            val bm = BitmapFactory.decodeStream(stream)
            stream.close()
            return@withContext bm
         } catch (_: MalformedURLException) {
            downloadError("Image URL malformed")
         } catch (_: IOException) {
            downloadError("IOError loading image")
         }
         null
      }
   }

   private fun saveAsJpeg(bm: Bitmap, directory: DocumentFile?, fileName: String) {
      val file = directory?.findOrCreateFile(fileName)
      if (file != null) {
         val stream = file.openOutputStream(requireContext(), false)
         if (stream != null) {
            bm.compress(Bitmap.CompressFormat.JPEG, 95, stream)
            stream.close()
            return
         }
      }
      downloadError("Failed to save image $fileName")
   }

   private fun downloadError(message: String) {
      activity?.runOnUiThread {
         Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
      }
   }
}

fun DocumentFile.findOrCreateFile(fileName: String): DocumentFile? {
   return findFile(fileName) ?: createFile("", fileName)
}

@Deprecated("Broken mimetype", ReplaceWith("findOrCreateFile(fileName)"))
fun DocumentFile.findOrCreateFile(mime: String, fileName: String): DocumentFile? {
   // do not append mimetype. This triggers android to append a fileending.
   // e.g. image.jpg with mimetype image/jpg will then be image.jpg.jpg
   return findOrCreateFile(fileName)
}

interface DownloadClickListener {
   fun doDownload()
}
