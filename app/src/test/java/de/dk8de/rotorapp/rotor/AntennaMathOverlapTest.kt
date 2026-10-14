package de.dk8de.rotorapp.rotor

import org.junit.Assert.assertEquals
import org.junit.Test

/** Zielwahl bei Rotoren, die über 360° hinaus fahren (GETMAXDG > 360). */
class AntennaMathOverlapTest {

    private val max430 = 430.0

    private fun reach(target: Double, current: Double?, max: Double = max430) =
        AntennaMath.nearestReachable(target, current, max)

    @Test
    fun `von 355 Grad aus wird 40 Grad als 400 Grad angefahren`() {
        assertEquals(400.0, reach(40.0, 355.0), 0.001)
    }

    @Test
    fun `kurzer Weg ohne Umdrehung bleibt unveraendert`() {
        assertEquals(40.0, reach(40.0, 10.0), 0.001)
        assertEquals(350.0, reach(350.0, 400.0), 0.001)
    }

    @Test
    fun `nur erreichbare Stellungen kommen in Frage`() {
        // 350+360 = 710 liegt ausserhalb von 430 — bleibt also 350.
        assertEquals(350.0, reach(350.0, 420.0), 0.001)
        // 80+360 = 440 ebenfalls ausserhalb.
        assertEquals(80.0, reach(80.0, 430.0), 0.001)
    }

    @Test
    fun `Standardrotor mit 360 Grad bekommt nie eine Umdrehung aufgeschlagen`() {
        assertEquals(40.0, reach(40.0, 355.0, max = 360.0), 0.001)
        assertEquals(10.0, reach(10.0, 350.0, max = 360.0), 0.001)
    }

    @Test
    fun `ohne bekannte Istposition bleibt es bei der Grundstellung`() {
        assertEquals(40.0, reach(40.0, null), 0.001)
    }

    @Test
    fun `Ziel wird vorher auf 0 bis 360 normiert`() {
        assertEquals(400.0, reach(400.0, 355.0), 0.001)
        assertEquals(400.0, reach(-320.0, 355.0), 0.001)
    }

    @Test
    fun `bei gleich langem Weg bleibt die kleinere Stellung stehen`() {
        // 180 und 540 waeren gleich weit; 540 liegt ohnehin ausserhalb.
        assertEquals(180.0, reach(180.0, 360.0), 0.001)
    }

    @Test
    fun `grosser Fahrbereich nutzt auch zwei Umdrehungen`() {
        // 720er-Rotor: von 700 aus ist 680 naeher als 320.
        assertEquals(680.0, reach(320.0, 700.0, max = 720.0), 0.001)
    }

    @Test
    fun `Textanzeige zeigt die Umdrehung, der Kompass die Peilung`() {
        val s = RotorLiveState(
            azDeg = 400.0,
            azSmoothDeg = 40.0,
            azTarget = 400.0,
            azMaxDeg = 430.0,
        )
        assertEquals(400.0, s.azIstText!!, 0.001)
        assertEquals(400.0, s.azSollText!!, 0.001)
        // Nadel bleibt auf der gewrappten Peilung
        assertEquals(40.0, s.displayAz(s.azSmoothDeg)!!, 0.001)
    }

    @Test
    fun `Standardrotor zeigt weiterhin nur 0 bis 360`() {
        val s = RotorLiveState(azDeg = 40.0, azSmoothDeg = 40.0, azTarget = 40.0)
        assertEquals(40.0, s.azIstText!!, 0.001)
        assertEquals(40.0, s.azSollText!!, 0.001)
    }
}
