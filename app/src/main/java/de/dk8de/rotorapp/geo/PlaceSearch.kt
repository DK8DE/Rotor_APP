package de.dk8de.rotorapp.geo

import android.net.Network
import de.dk8de.rotorapp.AppVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Treffer der Ortssuche. */
data class Place(
    val name: String,
    val lat: Double,
    val lon: Double,
)

/**
 * Suche nach Locator, Stadt oder Postleitzahl.
 *
 * Einen Locator rechnet die App selbst um — das geht ohne Netz. Alles andere
 * fragt Nominatim ab, bei Bedarf über [network] (WLAN am Rotor hat kein Internet).
 */
object PlaceSearch {

    private const val URL_BASE = "https://nominatim.openstreetmap.org/search"
    private const val LIMIT = 8
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 12_000

    /** Nominatim verlangt eine App-Kennung im User-Agent. */
    private val USER_AGENT =
        "RotorApp/${AppVersion.NAME} (+https://github.com/DK8DE/Rotor_APP)"

    /** Vollständiger Locator ab 4 Zeichen (JO31, JO31ab, JO31ab12). */
    private val LOCATOR = Regex("^[A-Ra-r]{2}[0-9]{2}([A-Xa-x]{2}([0-9]{2})?)?$")

    /** Locator ohne Netz auflösen, sonst null. */
    fun locatorHit(query: String): Place? {
        val q = query.trim().replace(" ", "")
        if (!LOCATOR.matches(q)) return null
        val (lat, lon) = GeoUtils.maidenheadToLatLon(q) ?: return null
        return Place(q.uppercase(), lat, lon)
    }

    /**
     * @param language Sprachcode für die Ortsnamen (z. B. `de`).
     * @return gefundene Orte, leer wenn es keinen Treffer gibt.
     * @throws Exception bei Netzfehlern — damit „nichts gefunden“ unterscheidbar bleibt.
     */
    suspend fun search(
        query: String,
        network: Network?,
        language: String,
    ): List<Place> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        locatorHit(q)?.let { return@withContext listOf(it) }

        val url = URL(
            URL_BASE +
                "?format=jsonv2" +
                "&limit=$LIMIT" +
                "&accept-language=" + URLEncoder.encode(language, "UTF-8") +
                "&q=" + URLEncoder.encode(q, "UTF-8"),
        )
        val conn = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                error("HTTP ${conn.responseCode}")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parse(body)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun parse(body: String): List<Place> {
        val arr = JSONArray(body)
        val out = mutableListOf<Place>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val lat = o.optString("lat").toDoubleOrNull() ?: continue
            val lon = o.optString("lon").toDoubleOrNull() ?: continue
            val name = o.optString("display_name")
            if (name.isBlank()) continue
            out += Place(name, lat, lon)
        }
        return out
    }
}
