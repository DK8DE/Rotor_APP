package de.dk8de.rotorapp.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun `hoehere Patch- Minor- und Major-Version gilt als neuer`() {
        assertTrue(UpdateChecker.isNewer("1.2.1", "1.2.0"))
        assertTrue(UpdateChecker.isNewer("1.3.0", "1.2.9"))
        assertTrue(UpdateChecker.isNewer("2.0.0", "1.9.9"))
    }

    @Test
    fun `gleiche oder aeltere Version gilt nicht als neuer`() {
        assertFalse(UpdateChecker.isNewer("1.2.0", "1.2.0"))
        assertFalse(UpdateChecker.isNewer("1.1.9", "1.2.0"))
    }

    @Test
    fun `Zahlen werden numerisch verglichen, nicht als Text`() {
        assertTrue(UpdateChecker.isNewer("1.10.0", "1.9.0"))
        assertFalse(UpdateChecker.isNewer("1.9.0", "1.10.0"))
    }

    @Test
    fun `fehlende Stellen zaehlen als null`() {
        assertFalse(UpdateChecker.isNewer("1.2", "1.2.0"))
        assertTrue(UpdateChecker.isNewer("1.2.1", "1.2"))
    }
}
