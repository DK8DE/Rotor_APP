package de.dk8de.rotorapp.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Raw TCP link that transports RS485 ASCII frames (#...$) without extra wrapping.
 */
class TcpLink {
    private val mutex = Mutex()
    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: BufferedOutputStream? = null
    private val rxBuffer = StringBuilder()

    @Volatile
    var isConnected: Boolean = false
        private set

    suspend fun connect(host: String, port: Int, timeoutMs: Int = 3000) = withContext(Dispatchers.IO) {
        mutex.withLock {
            disconnectLocked()
            val s = Socket()
            s.tcpNoDelay = true
            s.keepAlive = true
            s.connect(InetSocketAddress(host, port), timeoutMs)
            // Kurz: schnelle Timeouts zwischen Befehlen (ACK kommt meist sofort)
            s.soTimeout = 50
            socket = s
            input = BufferedInputStream(s.getInputStream())
            output = BufferedOutputStream(s.getOutputStream())
            rxBuffer.clear()
            isConnected = true
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        mutex.withLock { disconnectLocked() }
    }

    private fun disconnectLocked() {
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
    }

    suspend fun send(frame: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val out = output ?: error("Not connected")
            out.write(frame.toByteArray(StandardCharsets.US_ASCII))
            out.flush()
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
                    available > 0 -> rxBuffer.append(String(buf, 0, available, StandardCharsets.US_ASCII))
                    available < 0 -> {
                        disconnectLocked()
                        break
                    }
                    else -> {
                        // brief sleep via socket timeout path: peek with read when soTimeout set
                        try {
                            val n = inp.read(buf, 0, 1)
                            if (n > 0) {
                                rxBuffer.append(String(buf, 0, n, StandardCharsets.US_ASCII))
                            } else if (n < 0) {
                                disconnectLocked()
                                break
                            }
                        } catch (_: java.net.SocketTimeoutException) {
                            // expected when idle
                        } catch (_: Exception) {
                            disconnectLocked()
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
