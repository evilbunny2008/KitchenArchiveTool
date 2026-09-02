package com.odiousapps.nextcloudcookbook.util

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.logging.Logger

class Filesystem(var mContext: Context) {


   fun getInternalStoragePath(): File? {
      //val externalDir = mContext.filesDir
      val externalDir = mContext.getExternalFilesDir(null)
      //val externalDir = File(mContext.applicationInfo.dataDir)
      if( externalDir == null){
         Logger.getLogger(this::class.java.name).severe("Could not get Internal Storage Directory!")
         return null
      }
      return externalDir
   }

   /**
    * Delete a given folder or file.
    * If a folder is given, it will also delete all its content.
    *
    */
   fun deleteRecursive(fileOrDirectory: File) {
      if (fileOrDirectory.isDirectory) {
         val results = fileOrDirectory.listFiles()
         if(results != null){
            for (child in results) {
               deleteRecursive(child)
            }
         }
      }
      fileOrDirectory.delete()
   }

   private fun createInternalFoldersRecursive(path: String) {
      val externalDir = getInternalStoragePath() ?: return
      var file = File(externalDir.absolutePath)
      val folderlist = path.split('/')
      for(elem in folderlist){
         var tmpfile = File(file, elem)
         if(!file.exists()){
            file.mkdir()
         }
         file = tmpfile
      }
   }

   fun writeDataToInternal(folder: String, filename: String, content: ByteArray){
      createInternalFoldersRecursive(folder)
      val externalDir = getInternalStoragePath() ?: return
      val externalStorage = File(externalDir.absolutePath, folder)

      externalStorage.mkdirs()

      val file = File(externalStorage, filename)
      file.createNewFile()
      val stream = FileOutputStream(file)
      try {
         stream.write(content)
         stream.flush()
         stream.close()
      } catch (e: Exception) {
         Logger.getLogger(this::class.java.name).severe(e.message.toString())
         e.printStackTrace()
      }
   }

   fun readInternalFile(file: File): String {
      var content = ""
      try {
         val stream = FileInputStream(file)
         val inputStream = InputStreamReader(stream)
         val bufferedReader = BufferedReader(inputStream)

         var readString: String? = bufferedReader.readLine()
         while (readString != null) {
            content += readString
            readString = bufferedReader.readLine()
         }
         bufferedReader.close()
         inputStream.close()
         stream.close()
      } catch (_: FileNotFoundException){
         //Log.e(TAG, "readInternalFile: File not found! ${file.absoluteFile}")
      }
      return content
   }
}