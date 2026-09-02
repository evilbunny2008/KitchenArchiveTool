package de.micmun.android.nextcloudcookbook.services.sync

interface SyncProgressIndicatorInterface {

    fun updateProgress(item: Int, overall: Int, title: String)
}