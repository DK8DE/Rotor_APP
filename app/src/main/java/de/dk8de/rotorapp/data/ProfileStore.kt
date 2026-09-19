package de.dk8de.rotorapp.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.dk8de.rotorapp.ui.theme.HeatmapScale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.profileDataStore by preferencesDataStore(name = "rotor_profiles")

/** App-weite Anzeige-Einstellungen (nicht pro Rotor). */
data class UiDisplayPrefs(
    val showBeamOverlay: Boolean = true,
    val showStromRing: Boolean = false,
    val showDwellRing: Boolean = false,
    /** Standzeit bis Rot (Minuten). */
    val dwellFullMinutes: Float = 5f,
    /** Richtungs-Einteilung Standzeit-Ring (wie Bridge compass_dwell_sectors). */
    val dwellSectors: Int = 20,
    /** Eigene Strom-Heatmap-Skala (wie Bridge heatmap_custom_az). */
    val heatmapCustom: Boolean = false,
    val heatmapThrBlue: Int = 0,
    val heatmapNormMin: Int = 0,
    val heatmapNormMax: Int = 0,
    val heatmapThrRed: Int = 0,
) {
    /** Gültige Custom-Skala oder null (= Auto). */
    fun stromHeatmapScale(): HeatmapScale? {
        if (!heatmapCustom) return null
        val s = HeatmapScale(heatmapThrBlue, heatmapNormMin, heatmapNormMax, heatmapThrRed)
        return if (s.isValid()) s else null
    }
}

class ProfileStore(private val context: Context) {
    private val keyProfiles = stringPreferencesKey("profiles_json")
    private val keyActiveId = stringPreferencesKey("active_profile_id")
    private val keyShowBeamOverlay = booleanPreferencesKey("show_beam_overlay")
    private val keyShowStromRing = booleanPreferencesKey("show_strom_ring")
    private val keyShowDwellRing = booleanPreferencesKey("show_dwell_ring")
    private val keyDwellFullMinutes = floatPreferencesKey("dwell_full_minutes")
    private val keyDwellSectors = intPreferencesKey("dwell_sectors")
    private val keyHeatmapCustom = booleanPreferencesKey("heatmap_custom_az")
    private val keyHeatmapThrBlue = intPreferencesKey("heatmap_thr_blue_az")
    private val keyHeatmapNormMin = intPreferencesKey("heatmap_norm_min_az")
    private val keyHeatmapNormMax = intPreferencesKey("heatmap_norm_max_az")
    private val keyHeatmapThrRed = intPreferencesKey("heatmap_thr_red_az")

    val profiles: Flow<List<RotorProfile>> = context.profileDataStore.data.map { prefs ->
        decodeProfiles(prefs[keyProfiles])
    }

    val activeProfileId: Flow<String?> = context.profileDataStore.data.map { it[keyActiveId] }

    val uiDisplayPrefs: Flow<UiDisplayPrefs> = context.profileDataStore.data.map { prefs ->
        UiDisplayPrefs(
            showBeamOverlay = prefs[keyShowBeamOverlay] ?: true,
            showStromRing = prefs[keyShowStromRing] ?: false,
            showDwellRing = prefs[keyShowDwellRing] ?: false,
            dwellFullMinutes = (prefs[keyDwellFullMinutes] ?: 5f).coerceIn(0.5f, 120f),
            dwellSectors = (prefs[keyDwellSectors] ?: 20).coerceIn(10, 100),
            heatmapCustom = prefs[keyHeatmapCustom] ?: false,
            heatmapThrBlue = (prefs[keyHeatmapThrBlue] ?: 0).coerceIn(0, 65535),
            heatmapNormMin = (prefs[keyHeatmapNormMin] ?: 0).coerceIn(0, 65535),
            heatmapNormMax = (prefs[keyHeatmapNormMax] ?: 0).coerceIn(0, 65535),
            heatmapThrRed = (prefs[keyHeatmapThrRed] ?: 0).coerceIn(0, 65535),
        )
    }

    /** Öffnungswinkel-Overlay im AZ-Kompass (Standard: an). */
    val showBeamOverlay: Flow<Boolean> = uiDisplayPrefs.map { it.showBeamOverlay }

    suspend fun setShowBeamOverlay(enabled: Boolean) {
        context.profileDataStore.edit { prefs ->
            prefs[keyShowBeamOverlay] = enabled
        }
    }

    suspend fun setShowStromRing(enabled: Boolean) {
        context.profileDataStore.edit { prefs ->
            prefs[keyShowStromRing] = enabled
        }
    }

    suspend fun setShowDwellRing(enabled: Boolean) {
        context.profileDataStore.edit { prefs ->
            prefs[keyShowDwellRing] = enabled
        }
    }

    suspend fun setDwellFullMinutes(minutes: Float) {
        context.profileDataStore.edit { prefs ->
            prefs[keyDwellFullMinutes] = minutes.coerceIn(0.5f, 120f)
        }
    }

    suspend fun setDwellSectors(sectors: Int) {
        context.profileDataStore.edit { prefs ->
            prefs[keyDwellSectors] = sectors.coerceIn(10, 100)
        }
    }

    suspend fun setHeatmapCustom(enabled: Boolean) {
        context.profileDataStore.edit { prefs ->
            prefs[keyHeatmapCustom] = enabled
        }
    }

    suspend fun setHeatmapScale(
        custom: Boolean,
        thrBlue: Int,
        normMin: Int,
        normMax: Int,
        thrRed: Int,
    ) {
        context.profileDataStore.edit { prefs ->
            prefs[keyHeatmapCustom] = custom
            prefs[keyHeatmapThrBlue] = thrBlue.coerceIn(0, 65535)
            prefs[keyHeatmapNormMin] = normMin.coerceIn(0, 65535)
            prefs[keyHeatmapNormMax] = normMax.coerceIn(0, 65535)
            prefs[keyHeatmapThrRed] = thrRed.coerceIn(0, 65535)
        }
    }

    suspend fun saveProfiles(list: List<RotorProfile>, activeId: String?) {
        context.profileDataStore.edit { prefs ->
            prefs[keyProfiles] = encodeProfiles(list)
            if (activeId != null) {
                prefs[keyActiveId] = activeId
            } else {
                prefs.remove(keyActiveId)
            }
        }
    }

    suspend fun upsert(profile: RotorProfile, makeActive: Boolean = false) {
        context.profileDataStore.edit { prefs ->
            val current = decodeProfiles(prefs[keyProfiles]).toMutableList()
            val idx = current.indexOfFirst { it.id == profile.id }
            if (idx >= 0) current[idx] = profile else current.add(profile)
            prefs[keyProfiles] = encodeProfiles(current)
            if (makeActive || prefs[keyActiveId].isNullOrBlank()) {
                prefs[keyActiveId] = profile.id
            }
        }
    }

    suspend fun delete(id: String) {
        context.profileDataStore.edit { prefs ->
            val current = decodeProfiles(prefs[keyProfiles]).filterNot { it.id == id }
            prefs[keyProfiles] = encodeProfiles(current)
            if (prefs[keyActiveId] == id) {
                prefs[keyActiveId] = current.firstOrNull()?.id ?: ""
            }
        }
    }

    suspend fun setActive(id: String) {
        context.profileDataStore.edit { prefs ->
            prefs[keyActiveId] = id
        }
    }

    companion object {
        fun defaultProfiles(): List<RotorProfile> = listOf(
            RotorProfile(name = "Standard", host = "192.168.0.246", port = 8886)
        )

        fun encodeProfiles(list: List<RotorProfile>): String {
            val arr = JSONArray()
            list.forEach { p ->
                arr.put(
                    JSONObject()
                        .put("id", p.id)
                        .put("name", p.name)
                        .put("host", p.host)
                        .put("port", p.port)
                        .put("masterId", p.masterId)
                        .put("slaveAz", p.slaveAz)
                        .put("enableEl", p.enableEl)
                        .put("slaveEl", p.slaveEl)
                        .put("controllerId", p.controllerId)
                        .put("enableWind", p.enableWind)
                        .put("windDirMode", if (p.windDirMode.equals("to", true)) "to" else "from")
                        .put("lastElRotorType", p.lastElRotorType)
                )
            }
            return arr.toString()
        }

        fun decodeProfiles(raw: String?): List<RotorProfile> {
            if (raw.isNullOrBlank()) return defaultProfiles()
            return try {
                val arr = JSONArray(raw)
                if (arr.length() == 0) return defaultProfiles()
                buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        add(
                            RotorProfile(
                                id = o.optString("id", java.util.UUID.randomUUID().toString()),
                                name = o.optString("name", "Rotor"),
                                host = o.optString("host", "192.168.0.246"),
                                port = o.optInt("port", 8886),
                                masterId = o.optInt("masterId", 7),
                                slaveAz = o.optInt("slaveAz", 20),
                                enableEl = o.optBoolean("enableEl", false),
                                slaveEl = o.optInt("slaveEl", 21),
                                controllerId = o.optInt("controllerId", 2),
                                enableWind = o.optBoolean("enableWind", false),
                                windDirMode = if (o.optString("windDirMode", "from")
                                    .equals("to", ignoreCase = true)
                                ) {
                                    "to"
                                } else {
                                    "from"
                                },
                                lastElRotorType = o.optInt("lastElRotorType", 2).coerceIn(2, 3),
                            )
                        )
                    }
                }
            } catch (_: Exception) {
                defaultProfiles()
            }
        }
    }
}
