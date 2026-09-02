package com.odiousapps.nextcloudcookbook.services.sync

interface SyncProgressIndicatorInterface {

    fun updateProgress(item: Int, overall: Int, title: String)
}