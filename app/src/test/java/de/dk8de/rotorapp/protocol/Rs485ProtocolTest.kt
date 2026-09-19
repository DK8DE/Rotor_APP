package de.dk8de.rotorapp.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Rs485ProtocolTest {
    @Test
    fun buildGetPosDgExample() {
        val frame = Rs485Protocol.build(7, 20, "GETPOSDG", "0")
        assertEquals("#7:20:GETPOSDG:0:27$", frame)
    }

    @Test
    fun buildSetPosDgExample() {
        val frame = Rs485Protocol.build(7, 20, "SETPOSDG", "160,00")
        assertEquals("#7:20:SETPOSDG:160,00:187$", frame)
    }

    @Test
    fun parseAckGetPosDg() {
        val t = Rs485Protocol.parse("#20:7:ACK_GETPOSDG:160,00:187,00$")
        assertNotNull(t)
        assertTrue(t!!.ok)
        assertEquals(20, t.src)
        assertEquals(7, t.dst)
        assertEquals("ACK_GETPOSDG", t.cmd)
        assertEquals(160.0, Rs485Protocol.parseDegree(t.params)!!, 0.001)
    }

    @Test
    fun parseRejectsBadChecksum() {
        val t = Rs485Protocol.parse("#20:7:ACK_GETPOSDG:160,00:100$")
        assertNotNull(t)
        assertFalse(t!!.ok)
    }

    @Test
    fun formatDegUsesComma() {
        assertEquals("90,00", Rs485Protocol.formatDeg(90.0))
    }
}
