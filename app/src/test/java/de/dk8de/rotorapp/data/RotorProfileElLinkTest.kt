package de.dk8de.rotorapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wann bekommt EL eine zweite Verbindung — und auf welche Adresse geht sie? */
class RotorProfileElLinkTest {

    private val base = RotorProfile(
        id = "p",
        name = "Test",
        host = "192.168.0.246",
        port = 8886,
        enableEl = true,
    )

    @Test
    fun `ohne Haken teilt EL den Bus von AZ`() {
        val p = base.copy(elHost = "192.168.0.247", elPort = 8887)
        assertFalse(p.elSeparateLink())
        assertEquals("192.168.0.246", p.elHostEffective())
        assertEquals(8886, p.elPortEffective())
    }

    @Test
    fun `Haken mit eigener Adresse ergibt zweite Verbindung`() {
        val p = base.copy(elOwnLink = true, elHost = "192.168.0.247", elPort = 8887)
        assertTrue(p.elSeparateLink())
        assertEquals("192.168.0.247", p.elHostEffective())
        assertEquals(8887, p.elPortEffective())
    }

    @Test
    fun `leeres Feld oder gleiche Adresse bleibt eine Verbindung`() {
        assertFalse(base.copy(elOwnLink = true, elHost = "   ").elSeparateLink())
        assertFalse(
            base.copy(elOwnLink = true, elHost = "192.168.0.246", elPort = 8886).elSeparateLink()
        )
    }

    @Test
    fun `ohne Elevation bleibt es bei einer Verbindung`() {
        val p = base.copy(enableEl = false, elOwnLink = true, elHost = "192.168.0.247")
        assertFalse(p.elSeparateLink())
    }
}
