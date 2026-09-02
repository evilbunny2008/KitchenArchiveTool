package com.odiousapps.nextcloudcookbook.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.ImageView
import androidx.core.view.doOnDetach
import com.anggrayudi.storage.extension.launchOnUiThread
import com.anggrayudi.storage.file.getAbsolutePath
import com.odiousapps.nextcloudcookbook.settings.PreferenceData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLDecoder
import androidx.core.net.toUri

object ImageHelper {
    private val jobMap = mutableMapOf<ImageView, Job>()

    private suspend fun getBitmapFromUri(uri: Uri, context: Context): Bitmap? =
        withContext(Dispatchers.IO) {
            context.contentResolver.openFileDescriptor(uri, "r")?.use {
                val fileDescriptor = it.fileDescriptor
                BitmapFactory.decodeFileDescriptor(fileDescriptor)
            }
        }

    fun ImageView.setImageURIAsync(uri: Uri?, onSetImage: (ImageView.() -> Unit)? = null) {
        // cancel previously started job
        jobMap[this]?.cancel()
        setImageBitmap(null)
        // start job to load image
        uri?.let {
            // cancel job on detach, use only a single detach listener per image view even if
            // setImageURIAsync was called multiple times
            if(!jobMap.containsKey(this)) {
                doOnDetach {
                    jobMap.remove(this)?.cancel()
                }
            }
            // start job to load new image
            jobMap[this] = launchOnUiThread {
                try {
                    onSetImage?.invoke(this@setImageURIAsync)
                    setImageBitmap(getBitmapFromUri(it, context))
                } catch (_: SecurityException) {
                    PreferenceData.getInstance().setStorageAccessed(false)
                }
            }
        }
    }

    fun String?.toImageUri(context: Context): Uri? = this?.takeIf { it.isNotEmpty() }?.let {
        val externalDir = Filesystem(context).getInternalStoragePath() ?: return null

        // required, because internal storage may contain special chars that are
        // encoded and will result in unreadable images
        if (it.startsWith("file://${externalDir.absolutePath}")) {
            val img = it.replace("file://", "")
            val imgUrl = URLDecoder.decode(img, "UTF-8")
            Uri.fromFile(File(imgUrl))
        } else {
            StorageManager.getImageFromString(context, it)?.run {
                if(canRead()) uri else getAbsolutePath(context).toUri()
            }
        }
    }
}