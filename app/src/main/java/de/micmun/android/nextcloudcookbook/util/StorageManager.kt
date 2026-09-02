/*
 * StorageManager.kt
 *
 * Copyright 2021 by MicMun
 */
package de.micmun.android.nextcloudcookbook.util

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.anggrayudi.storage.file.DocumentFileCompat
import com.anggrayudi.storage.file.DocumentFileType

/**
 * Manages the storage access.
 *
 * @author MicMun
 * @version 1.3, 29.08.21
 */
class StorageManager {
   companion object {
      fun getDocumentFromString(context: Context, path: String): DocumentFile? {
         return getDocumentFile(context, path, DocumentFileType.FOLDER)
      }

      fun getImageFromString(context: Context, path: String): DocumentFile? {
         return getDocumentFile(context, path, DocumentFileType.FILE)
      }

      private fun getDocumentFile(context: Context, path: String, type: DocumentFileType): DocumentFile? {
         return if (path.startsWith("content:")) {
            try {
               DocumentFile.fromTreeUri(context, Uri.parse(path))
            } catch (e: IllegalArgumentException) {
               null
            }
         } else {
            try {
               DocumentFileCompat.fromFullPath(context, path, type)
            } catch (e: IllegalArgumentException) {
               null
            }
         }
      }
   }
}
