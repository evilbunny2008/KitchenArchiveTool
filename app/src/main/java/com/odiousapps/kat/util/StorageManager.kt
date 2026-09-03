/*
 * StorageManager.kt
 *
 * Copyright 2021 by MicMun
 */
package com.odiousapps.kat.util

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.anggrayudi.storage.file.DocumentFileCompat
import com.anggrayudi.storage.file.DocumentFileType
import androidx.core.net.toUri

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
               DocumentFile.fromTreeUri(context, path.toUri())
            } catch (_: IllegalArgumentException) {
               null
            }
         } else {
            try {
               DocumentFileCompat.fromFullPath(context, path, type)
            } catch (_: IllegalArgumentException) {
               null
            }
         }
      }
   }
}
