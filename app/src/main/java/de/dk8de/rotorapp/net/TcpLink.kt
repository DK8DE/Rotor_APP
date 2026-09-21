package de.dk8de.rotorapp.net

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Raw TCP link that transports RS485 ASCII frames (#...$) without extra wrapping.
 * Verbindungen laufen bewusst über WLAN (nicht Mobilfunk).
 */
class TcpLink(context: Context) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: BufferedOutputStream? = null
    private val rxBuffer = StringBuilder()

    @Volatile
    var isConnected: Boolean = false
        private set

    /** Wird aufgerufen wenn die Verbindung unerwartet abreißt (Socket tot). */
    @Volatile
    var onDisconnected: (() -> Unit)? = null

    @Volatile
    private var lastRxMs: Long = 0L

    /**
     * Zeit seit dem letzten empfangenen Byte. Ein stromlos abgeschalteter Rotor
     * schließt den Socket nicht — Reads liefern dann nur Timeouts statt Fehler.
     * Über die Funkstille lässt sich so ein halb offener Socket erkennen.
     */
    val silenceMs: Long
        get() = if (!isConnected || lastRxMs == 0L) {
            0L
        } else {
            SystemClock.elapsedRealtime() - lastRxMs
        }

    suspend fun connect(host: String, port: Int, timeoutMs: Int = 3000) = withContext(Dispatchers.IO) {
        mutex.withLock {
            disconnectLocked(notify = false)
            val wifi = WifiNetworkBinder.awaitWifiNetwork(appContext, timeoutMs = 1_500L)
            val s = try {
                if (wifi != null) {
                    WifiNetworkBinder.connectViaWifi(wifi, host, port, timeoutMs)
                } else {
                    WifiNetworkBinder.connectDefault(host, port, timeoutMs)
                }
            } catch (first: Exception) {
                if (wifi != null) {
                    try {
                        WifiNetworkBinder.connectDefault(host, port, timeoutMs)
                    } catch (second: Exception) {
                        error(
                            "Verbindung fehlgeschlagen (${first.message ?: first}). " +
                                "Fallback: ${second.message ?: second}",
                        )
                    }
                } else {
                    throw first
                }
            }
            s.soTimeout = 50
            socket = s
            input = BufferedInputStream(s.getInputStream())
            output = BufferedOutputStream(s.getOutputStream())
            rxBuffer.clear()
            lastRxMs = SystemClock.elapsedRealtime()
            isConnected = true
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        mutex.withLock { disconnectLocked(notify = false) }
    }

    private fun disconnectLocked(notify: Boolean) {
        val was = isConnected
        isConnected = false
        try {
            output?.close()
        } catch (_: Exception) {
        }
        try {
            input?.close()
        } catch (_: Exception) {
        }
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        output = null
        input = null
        socket = null
        rxBuffer.clear()
        if (notify && was) {
            runCatching { onDisconnected?.invoke() }
        }
    }

    suspend fun send(frame: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val out = output ?: error("Not connected")
            try {
                out.write(frame.toByteArray(StandardCharsets.US_ASCII))
                out.flush()
            } catch (e: Exception) {
                disconnectLocked(notify = true)
                throw e
            }
        }
    }

    /**
     * Read available data and return complete #...$ frames.
     */
    suspend fun readFrames(waitMs: Long = 250): List<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val inp = input ?: return@withLock emptyList()
            val deadline = System.currentTimeMillis() + waitMs
            val buf = ByteArray(512)
            while (System.currentTimeMillis() <= deadline) {
                val available = try {
                    if (inp.available() > 0) inp.read(buf) else 0
                } catch (_: Exception) {
                    -1
                }
                when {
                    available > 0 -> {
                        lastRxMs = SystemClock.elapsedRealtime()
                        rxBuffer.append(String(buf, 0, available, StandardCharsets.US_ASCII))
                    }
                    available < 0 -> {
                        disconnectLocked(notify = true)
                        break
                    }
                    else -> {
                        try {
                            val n = inp.read(buf, 0, 1)
                            if (n > 0) {
                                lastRxMs = SystemClock.elapsedRealtime()
                                rxBuffer.append(String(buf, 0, n, StandardCharsets.US_ASCII))
                            } else if (n < 0) {
                                disconnectLocked(notify = true)
                                break
                            }
                        } catch (_: java.net.SocketTimeoutException) {
                            // expected when idle
                        } catch (_: Exception) {
                            disconnectLocked(notify = true)
                            break
                        }
                    }
                }
                if (rxBuffer.contains('$')) break
            }
            extractFrames()
        }
    }

    private fun extractFrames(): List<String> {
        val frames = mutableListOf<String>()
        while (true) {
            val start = rxBuffer.indexOf("#")
            if (start < 0) {
                rxBuffer.clear()
                break
            }
            if (start > 0) {
                rxBuffer.delete(0, start)
            }
            val end = rxBuffer.indexOf("$")
            if (end < 0) break
            frames += rxBuffer.substring(0, end + 1)
            rxBuffer.delete(0, end + 1)
        }
        return frames
    }
}
