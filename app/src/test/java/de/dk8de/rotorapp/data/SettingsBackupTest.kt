package de.dk8de.rotorapp.data

import de.dk8de.rotorapp.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupTest {

    private val profiles = listOf(
        RotorProfile(
            id = "p1",
            name = "Beam 20m",
            host = "192.168.0.246",
            port = 8886,
            masterId = 7,
            slaveAz = 20,
            enableEl = true,
            slaveEl = 21,
            controllerId = 2,
            enableWind = true,
            windDirMode = "to",
            lastElRotorType = 3,
        ),
        RotorProfile(id = "p2", name = "Vertikal", host = "10.0.0.5", port = 1234),
    )

    private val favorites = listOf(
        PositionFavorite(id = "f1", name = "USA", azDeg = 300.0, elDeg = 12.0),
        PositionFavorite(id = "f2", name = "JA", azDeg = 40.5, elDeg = 0.0),
    )

    private val display = UiDisplayPrefs(
        showBeamOverlay = false,
        showStromRing = true,
        showDwellRing = true,
        dwellFullMinutes = 7.5f,
        dwellSectors = 36,
        heatmapCustom = true,
        heatmapThrBlue = 100,
        heatmapNormMin = 200,
        heatmapNormMax = 900,
        heatmapThrRed = 1200,
        locationLat = 51.25,
        locationLon = 7.5,
        locationLocator = "JO31",
        appLanguage = AppLanguage.EN,
    )

    private val content = SettingsBackup.Content(
        profiles = profiles,
        activeProfileId = "p2",
        favorites = favorites,
        display = display,
    )

    @Test
    fun `Sicherung ueberlebt Schreiben und Lesen unveraendert`() {
        val parsed = SettingsBackup.parse(SettingsBackup.build(content, "1.2.3"))
        assertNotNull(parsed)
        assertEquals(profiles, parsed!!.profiles)
        assertEquals(favorites, parsed.favorites)
        assertEquals(display, parsed.display)
        assertEquals("p2", parsed.activeProfileId)
    }

    @Test
    fun `fremde oder kaputte Datei wird abgelehnt`() {
        assertNull(SettingsBackup.parse(""))
        assertNull(SettingsBackup.parse("kein json"))
        assertNull(SettingsBackup.parse("""{"app":"com.fremde.app","profiles":[{"id":"x"}]}"""))
        assertNull(SettingsBackup.parse("""{"app":"de.dk8de.rotorapp","profiles":[]}"""))
        assertNull(SettingsBackup.parse("""{"app":"de.dk8de.rotorapp"}"""))
    }

    @Test
    fun `Datei ohne Favoriten und Anzeigeblock bleibt nutzbar`() {
        val minimal = """
            {"app":"de.dk8de.rotorapp","backupVersion":1,
             "profiles":[{"id":"p9","name":"Nur AZ","host":"1.2.3.4","port":8886}]}
        """.trimIndent()
        val parsed = SettingsBackup.parse(minimal)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.profiles.size)
        assertEquals("Nur AZ", parsed.profiles.first().name)
        assertTrue(parsed.favorites.isEmpty())
        assertNull(parsed.display)
    }

    @Test
    fun `unbekannte aktive ID faellt auf das erste Profil zurueck`() {
        val json = SettingsBackup.build(content.copy(activeProfileId = "geloescht"), "1.2.3")
        assertEquals("p1", SettingsBackup.parse(json)?.activeProfileId)
    }

    @Test
    fun `unsinnige Werte werden auf gueltige Bereiche begrenzt`() {
        val json = """
            {"app":"de.dk8de.rotorapp",
             "profiles":[{"id":"p1","name":"A","host":"h","port":1}],
             "display":{"dwellSectors":9999,"dwellFullMinutes":0.01,
                        "locationLat":95.0,"appLanguage":"klingon"}}
        """.trimIndent()
        val d = SettingsBackup.parse(json)?.display
        assertNotNull(d)
        assertEquals(100, d!!.dwellSectors)
        assertEquals(0.5f, d.dwellFullMinutes, 0.001f)
        assertEquals(90.0, d.locationLat, 0.001)
        assertEquals(AppLanguage.SYSTEM, d.appLanguage)
    }

    @Test
    fun `Dateiname traegt das Datum`() {
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        assertEquals("rotorapp-backup-$day.json", SettingsBackup.fileName())
    }
}
