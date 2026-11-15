package com.traceguard.antitheft.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.location.LocationManager

data class NetworkStatus(
    val connectionType: String?,
    val internetValidated: Boolean?,
    val wifiSsid: String?,
    val networkOperator: String? = null,
)

class NetworkRepository(private val context: Context) {
    fun read(): NetworkStatus {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork
        val capabilities = network?.let(manager::getNetworkCapabilities)
        val kind = when {
            capabilities == null -> "none"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "unknown"
        }
        val validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return NetworkStatus(kind, validated, readWifiSsid().takeIf { kind == "wifi" })
    }

    @Suppress("DEPRECATION")
    private fun readWifiSsid(): String? {
        val locationEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.getSystemService(LocationManager::class.java).isLocationEnabled
        } else {
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE, 0) != 0
        }
        if (!locationEnabled) return null
        return try {
            val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ssid = manager.connectionInfo?.ssid?.removeSurrounding("\"")
            ssid?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        } catch (_: SecurityException) {
            null
        }
    }
}
