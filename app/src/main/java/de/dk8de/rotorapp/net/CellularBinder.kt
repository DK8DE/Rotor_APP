package de.dk8de.rotorapp.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

/**
 * Hält ein Mobilfunk-Network-Handle bereit, während WLAN am Rotor-AP hängt.
 *
 * Rotor-AP = WLAN ohne Internet: Kartenkacheln kommen dort nicht durch. Mit dem
 * Handle kann gezielt über LTE geladen werden, der Rotor-Socket bleibt am WLAN
 * (siehe [WifiNetworkBinder]). Voraussetzung: mobile Daten sind eingeschaltet.
 */
object CellularBinder {

    private const val TAG = "CellularBinder"

    private var callback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var cellular: Network? = null

    /** Mobilfunk-Network, oder null solange keines verfügbar ist. */
    fun network(): Network? = cellular

    /** Fordert Mobilfunk mit Internet an (idempotent). */
    @Synchronized
    fun enable(context: Context) {
        if (callback != null) return
        val cm = WifiNetworkBinder.connectivity(context)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cellular = network
            }

            override fun onLost(network: Network) {
                if (cellular == network) cellular = null
            }

            override fun onUnavailable() {
                cellular = null
            }
        }
        try {
            cm.requestNetwork(request, cb)
            callback = cb
        } catch (e: Exception) {
            // z. B. kein Mobilfunk-Modem oder Berechtigung verweigert
            Log.w(TAG, "requestNetwork fehlgeschlagen: ${e.message}")
            cellular = null
        }
    }

    /** Anforderung wieder freigeben. */
    @Synchronized
    fun disable(context: Context) {
        val cb = callback ?: return
        callback = null
        cellular = null
        runCatching { WifiNetworkBinder.connectivity(context).unregisterNetworkCallback(cb) }
    }

    /** true, wenn das aktive Netz Internet hat (dann braucht die Karte kein LTE). */
    fun activeNetworkHasInternet(context: Context): Boolean {
        val cm = WifiNetworkBinder.connectivity(context)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
