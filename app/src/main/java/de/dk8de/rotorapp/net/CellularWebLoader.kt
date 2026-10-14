package de.dk8de.rotorapp.net

import android.net.Network
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lädt WebView-Ressourcen (Kartenkacheln) über ein bestimmtes [Network].
 *
 * Liefert null, wenn die Anfrage nicht umgeleitet werden kann oder der Abruf
 * scheitert — dann lädt der WebView wie gewohnt über das Standard-Netz.
 */
object CellularWebLoader {

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 15_000

    /** Header, die der WebView nicht mitbekommen darf (Inhalt ist schon entpackt). */
    private val DROPPED_RESPONSE_HEADERS = setOf(
        "content-encoding",
        "content-length",
        "transfer-encoding",
    )

    fun load(
        network: Network,
        request: WebResourceRequest,
        userAgent: String,
    ): WebResourceResponse? {
        if (!request.method.equals("GET", ignoreCase = true)) return null
        val scheme = request.url.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null

        return try {
            val conn = network.openConnection(URL(request.url.toString())) as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            request.requestHeaders.forEach { (name, value) ->
                // Accept-Encoding bleibt der Verbindung überlassen, sonst käme gepackter Inhalt an.
                if (!name.equals("Accept-Encoding", ignoreCase = true)) {
                    conn.setRequestProperty(name, value)
                }
            }
            if (conn.getRequestProperty("User-Agent") == null) {
                conn.setRequestProperty("User-Agent", userAgent)
            }

            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val contentType = conn.contentType.orEmpty()
            val mime = contentType.substringBefore(';').trim().ifEmpty { "application/octet-stream" }
            val charset = contentType.substringAfter("charset=", "").trim().ifEmpty { null }

            val headers = buildMap {
                conn.headerFields.forEach { (name, values) ->
                    if (name != null && name.lowercase() !in DROPPED_RESPONSE_HEADERS) {
                        put(name, values.joinToString(", "))
                    }
                }
            }

            WebResourceResponse(
                mime,
                charset,
                status,
                conn.responseMessage?.ifBlank { null } ?: "OK",
                headers,
                stream,
            )
        } catch (_: Exception) {
            null
        }
    }
}
