package de.dk8de.rotorapp.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.resume

/**
 * Bindet Rotor-TCP möglichst an WLAN (nicht Mobilfunk).
 *
 * Heim-WLAN ohne Internet: Android nutzt sonst oft LTE — lokale IPs scheitern.
 * Wenn kein Wi‑Fi-[Network]-Handle gefunden wird, fällt der Aufrufer auf
 * Normal-Routing zurück (z. B. nur WLAN aktiv).
 */
object WifiNetworkBinder {

    fun connectivity(context: Context): ConnectivityManager =
        context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** Bereits verbundenes Wi‑Fi-Network, oder null. */
    fun findWifiNetwork(context: Context): Network? {
        val cm = connectivity(context)

        cm.activeNetwork?.let { active ->
            val caps = cm.getNetworkCapabilities(active)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                return active
            }
        }

        @Suppress("DEPRECATION")
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return network
            }
        }
        return null
    }

    /**
     * Wi‑Fi-Network finden; wartet kurz per [registerNetworkCallback]     * (meldet auch schon bestehende Netze).
     */
    suspend fun awaitWifiNetwork(context: Context, timeoutMs: Long = 3_000L): Network? {
        findWifiNetwork(context)?.let { return it }

        val cm = connectivity(context)
        val mainHandler = Handler(Looper.getMainLooper())
        val found = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build()
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        finish(network)
                    }

                    private fun finish(network: Network?) {
                        runCatching { cm.unregisterNetworkCallback(this) }
                        if (cont.isActive) cont.resume(network)
                    }
                }
                cont.invokeOnCancellation {
                    runCatching { cm.unregisterNetworkCallback(cb) }
                }
                try {
                    // Callback muss auf Looper-Thread registriert werden
                    cm.registerNetworkCallback(request, cb, mainHandler)
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(null)
                    return@suspendCancellableCoroutine
                }
                // Race: Netz war schon da, onAvailable kommt ggf. sofort — zusätzlich prüfen
                mainHandler.post {
                    if (!cont.isActive) return@post
                    findWifiNetwork(context)?.let { wifi ->
                        runCatching { cm.unregisterNetworkCallback(cb) }
                        if (cont.isActive) cont.resume(wifi)
                    }
                }
            }
        }
        return found ?: findWifiNetwork(context)
    }

    /**
     * Socket über Wi‑Fi öffnen und verbinden.
     * @return true wenn über Wi‑Fi-Network verbunden
     */
    fun connectViaWifi(
        wifi: Network,
        host: String,
        port: Int,
        timeoutMs: Int,
    ): Socket {
        val s = wifi.socketFactory.createSocket()
        s.tcpNoDelay = true
        s.keepAlive = true
        s.connect(InetSocketAddress(host, port), timeoutMs)
        return s
    }

    fun connectDefault(host: String, port: Int, timeoutMs: Int): Socket {
        val s = Socket()
        s.tcpNoDelay = true
        s.keepAlive = true
        s.connect(InetSocketAddress(host, port), timeoutMs)
        return s
    }
}
