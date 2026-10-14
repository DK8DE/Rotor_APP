package de.dk8de.rotorapp.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Locator erkennt die App selbst, alles andere geht an die Ortssuche. */
class PlaceSearchTest {

    @Test
    fun `Locator wird ohne Netz aufgeloest`() {
        val hit = PlaceSearch.locatorHit("jo31ab")
        assertNotNull(hit)
        assertEquals("JO31AB", hit!!.name)
        assertEquals(51.06, hit.lat, 0.5)
        assertEquals(6.04, hit.lon, 0.5)
    }

    @Test
    fun `kurzer Locator und Leerzeichen stoeren nicht`() {
        assertNotNull(PlaceSearch.locatorHit("JO31"))
        assertNotNull(PlaceSearch.locatorHit(" JN49 "))
    }

    @Test
    fun `Stadt und PLZ sind keine Locator`() {
        // Gingen sie als Locator durch, käme die Ortssuche nie zum Zug.
        assertNull(PlaceSearch.locatorHit("Mannheim"))
        assertNull(PlaceSearch.locatorHit("68159"))
        assertNull(PlaceSearch.locatorHit("Bad Kreuznach"))
        assertNull(PlaceSearch.locatorHit("JO"))
        assertNull(PlaceSearch.locatorHit(""))
    }
}
