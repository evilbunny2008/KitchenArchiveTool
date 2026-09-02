package com.odiousapps.nextcloudcookbook.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

class ConnectivityCheck {

    companion object {
        fun isConnectedToWifi(context: Context?): Boolean {
            val connMgr =
                context?.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val capabilities = connMgr.getNetworkCapabilities(connMgr.activeNetwork)
            return capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }

        fun isConnected(context: Context?): Boolean {
            val connMgr =
                context?.applicationContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            return connMgr.activeNetworkInfo?.isConnected ?: false
        }
    }
}