package com.odiousapps.kat.services.sync

interface SyncProgressIndicatorInterface {

    fun updateProgress(item: Int, overall: Int, title: String)
}
