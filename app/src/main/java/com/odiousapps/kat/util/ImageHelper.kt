package com.odiousapps.kat.util

import android.content.ContentResolver
import android.content.Context
import android.graphics.drawable.Drawable
import android.net.Uri
import android.widget.ImageView
import com.anggrayudi.storage.extension.launchOnUiThread
import com.anggrayudi.storage.file.getAbsolutePath
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.signature.ObjectKey
import com.odiousapps.kat.settings.PreferenceData
import java.io.File
import java.net.URLDecoder
import androidx.core.net.toUri

object ImageHelper {
    // Spots a lost storage permission among a failed load's causes, the same
    // case the old hand-rolled loader caught as a SecurityException.
    private val storageAccessListener = object : RequestListener<Drawable> {
        override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
            if (e?.rootCauses?.any { it is SecurityException } == true) {
                launchOnUiThread { PreferenceData.getInstance().setStorageAccessed(false) }
            }
            // false: let Glide carry on and clear the view, as for any other
            // failure (missing file, undecodable image)
            return false
        }

        override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean) = false
    }

    fun ImageView.setImageURIAsync(uri: Uri?, onSetImage: (ImageView.() -> Unit)? = null) {
        // Glide rather than BitmapFactory: it decodes the photo downsampled
        // to this view's size instead of at full camera resolution, keeps
        // recent ones in memory for scrolling back through the list, and
        // reuses bitmap memory -- Play Console flags full-size manual
        // decoding as a performance problem. into() also cancels whatever
        // this view was loading before and stops when the view's screen
        // goes away, which the old per-view job map did by hand.
        if (uri == null) {
            Glide.with(this).clear(this)
            setImageDrawable(null)
            return
        }
        onSetImage?.invoke(this)
        Glide.with(this)
            .load(uri)
            // The recipe photos are already local files, so a second copy in
            // Glide's disk cache would only waste space.
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .signature(uri.versionKey())
            .listener(storageAccessListener)
            .into(this)
    }

    // Sync rewrites a recipe's photo in place under the same name when it
    // changes, so tie the memory-cached copy to the file's modification time
    // and a replaced photo shows up instead of the stale one.
    private fun Uri.versionKey(): ObjectKey =
        ObjectKey(if (scheme == ContentResolver.SCHEME_FILE) path?.let { File(it).lastModified() } ?: 0L else 0L)

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
