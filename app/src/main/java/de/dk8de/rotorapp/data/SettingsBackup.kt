package de.dk8de.rotorapp.data

import de.dk8de.rotorapp.AppLanguage
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dateiformat der Einstellungs-Sicherung (Format-Version 1).
 *
 * Bewusst getrennt vom [ProfileStore]: So lässt sich das Format ohne DataStore
 * und ohne Gerät prüfen — ein kaputter Import würde sonst alle Profile kosten.
 */
object SettingsBackup {
    private const val APP = "de.dk8de.rotorapp"
    private const val FORMAT_VERSION = 1

    /** Inhalt einer Sicherung; [display] fehlt bei Dateien ohne Anzeige-Block. */
    data class Content(
        val profiles: List<RotorProfile>,
        val activeProfileId: String,
        val favorites: List<PositionFavorite>,
        val display: UiDisplayPrefs?,
    )

    fun fileName(now: Date = Date()): String =
        "rotorapp-backup-${format("yyyy-MM-dd", now)}.json"

    fun build(content: Content, appVersion: String, now: Date = Date()): String =
        JSONObject()
            .put("app", APP)
            .put("backupVersion", FORMAT_VERSION)
            .put("appVersion", appVersion)
            .put("createdAt", format("yyyy-MM-dd'T'HH:mm:ss", now))
            .put("profiles", JSONArray(ProfileStore.encodeProfiles(content.profiles)))
            .put("activeProfileId", content.activeProfileId)
            .put("favorites", JSONArray(ProfileStore.encodeFavorites(content.favorites)))
            .apply { content.display?.let { put("display", encodeDisplay(it)) } }
            .toString(2)

    /** @return null bei fremder, leerer oder kaputter Datei — Bestand bleibt dann unberührt. */
    fun parse(text: String): Content? {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (obj.optString("app") != APP) return null
        // Profile sind Pflicht: decodeProfiles liefert sonst still die Defaults.
        val arr = obj.optJSONArray("profiles")?.takeIf { it.length() > 0 } ?: return null
        val profiles = ProfileStore.decodeProfiles(arr.toString())
        val active = obj.optString("activeProfileId")
            .takeIf { id -> profiles.any { it.id == id } }
            ?: profiles.first().id
        return Content(
            profiles = profiles,
            activeProfileId = active,
            favorites = ProfileStore.decodeFavorites(obj.optJSONArray("favorites")?.toString()),
            display = obj.optJSONObject("display")?.let { decodeDisplay(it) },
        )
    }

    private fun format(pattern: String, now: Date): String =
        SimpleDateFormat(pattern, Locale.US).format(now)

    private fun encodeDisplay(d: UiDisplayPrefs): JSONObject = JSONObject()
        .put("showBeamOverlay", d.showBeamOverlay)
        .put("showStromRing", d.showStromRing)
        .put("showDwellRing", d.showDwellRing)
        .put("dwellFullMinutes", d.dwellFullMinutes.toDouble())
        .put("dwellSectors", d.dwellSectors)
        .put("heatmapCustom", d.heatmapCustom)
        .put("heatmapThrBlue", d.heatmapThrBlue)
        .put("heatmapNormMin", d.heatmapNormMin)
        .put("heatmapNormMax", d.heatmapNormMax)
        .put("heatmapThrRed", d.heatmapThrRed)
        .put("locationLat", d.locationLat)
        .put("locationLon", d.locationLon)
        .put("locationLocator", d.locationLocator)
        .put("appLanguage", d.appLanguage)

    private fun decodeDisplay(o: JSONObject): UiDisplayPrefs {
        val def = UiDisplayPrefs()
        val lang = o.optString("appLanguage", def.appLanguage)
        return UiDisplayPrefs(
            showBeamOverlay = o.optBoolean("showBeamOverlay", def.showBeamOverlay),
            showStromRing = o.optBoolean("showStromRing", def.showStromRing),
            showDwellRing = o.optBoolean("showDwellRing", def.showDwellRing),
            dwellFullMinutes = o.optDouble("dwellFullMinutes", def.dwellFullMinutes.toDouble())
                .toFloat().coerceIn(0.5f, 120f),
            dwellSectors = o.optInt("dwellSectors", def.dwellSectors).coerceIn(10, 100),
            heatmapCustom = o.optBoolean("heatmapCustom", def.heatmapCustom),
            heatmapThrBlue = o.optInt("heatmapThrBlue", 0).coerceIn(0, 65535),
            heatmapNormMin = o.optInt("heatmapNormMin", 0).coerceIn(0, 65535),
            heatmapNormMax = o.optInt("heatmapNormMax", 0).coerceIn(0, 65535),
            heatmapThrRed = o.optInt("heatmapThrRed", 0).coerceIn(0, 65535),
            locationLat = o.optDouble("locationLat", def.locationLat).coerceIn(-90.0, 90.0),
            locationLon = o.optDouble("locationLon", def.locationLon).coerceIn(-180.0, 180.0),
            locationLocator = o.optString("locationLocator", def.locationLocator).trim(),
            appLanguage = when (lang) {
                AppLanguage.DE, AppLanguage.EN -> lang
                else -> AppLanguage.SYSTEM
            },
        )
    }
}
