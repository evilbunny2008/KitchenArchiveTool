package com.odiousapps.kat.services.sync

fun interface SyncProgressIndicatorInterface {

    fun updateProgress(item: Int, overall: Int, title: String)
}
