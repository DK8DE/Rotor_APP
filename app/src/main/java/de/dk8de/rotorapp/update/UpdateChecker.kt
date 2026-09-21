package de.dk8de.rotorapp.update

import de.dk8de.rotorapp.AppVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Neuestes GitHub-Release, sofern neuer als die installierte Version. */
data class UpdateInfo(
    /** Reine Versionsnummer ohne „v“, z. B. `"1.2.0"`. */
    val version: String,
    /** Release-Seite mit Changelog. */
    val pageUrl: String,
    /** Direkter APK-Download, falls das Release ein APK anhängt. */
    val apkUrl: String?,
) {
    val downloadUrl: String get() = apkUrl ?: pageUrl
}

/**
 * Prüft die GitHub-Releases auf eine neuere Version.
 * Läuft über die Standard-Route (Internet), nicht über die WLAN-Bindung des Rotors.
 */
object UpdateChecker {
    private const val LATEST_API =
        "https://api.github.com/repos/DK8DE/Rotor_APP/releases/latest"
    private const val TIMEOUT_MS = 6_000

    /**
     * @return neueres Release, oder null wenn die installierte Version aktuell ist.
     * Wirft bei Netz-/Parse-Fehlern — nur so lässt sich „aktuell“ von
     * „nicht erreichbar“ unterscheiden.
     */
    suspend fun findNewerRelease(): UpdateInfo? = withContext(Dispatchers.IO) {
        val latest = parse(fetchLatest()) ?: return@withContext null
        if (isNewer(latest.version, AppVersion.NAME)) latest else null
    }

    private fun fetchLatest(): String {
        val conn = (URL(LATEST_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            // GitHub weist Anfragen ohne User-Agent ab.
            setRequestProperty("User-Agent", "RotorApp/${AppVersion.NAME}")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                error("HTTP ${conn.responseCode}")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(json: String): UpdateInfo? {
        val obj = JSONObject(json)
        if (obj.optBoolean("draft") || obj.optBoolean("prerelease")) return null
        val version = obj.optString("tag_name").trim().removePrefix("v").removePrefix("V")
        if (version.isBlank()) return null
        val assets = obj.optJSONArray("assets")
        var apkUrl: String? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    apkUrl = a.optString("browser_download_url").ifBlank { null }
                    break
                }
            }
        }
        return UpdateInfo(
            version = version,
            pageUrl = obj.optString("html_url").ifBlank { "https://github.com/DK8DE/Rotor_APP/releases" },
            apkUrl = apkUrl,
        )
    }

    /** Vergleich nach MAJOR.MINOR.PATCH; fehlende Stellen zählen als 0. */
    internal fun isNewer(remote: String, installed: String): Boolean {
        val r = parts(remote)
        val i = parts(installed)
        for (idx in 0 until maxOf(r.size, i.size)) {
            val a = r.getOrElse(idx) { 0 }
            val b = i.getOrElse(idx) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun parts(v: String): List<Int> =
        v.split('.', '-', '+').mapNotNull { it.takeWhile(Char::isDigit).toIntOrNull() }
}
