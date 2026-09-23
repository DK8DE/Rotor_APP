package de.dk8de.rotorapp.rotor

import de.dk8de.rotorapp.net.TcpLink
import de.dk8de.rotorapp.protocol.Rs485Protocol
import de.dk8de.rotorapp.protocol.Telegram
import kotlinx.coroutines.delay

class RotorClient(
    private val link: TcpLink,
    var masterId: Int = 7,
) {
    /** Wird für jedes empfangene Telegramm aufgerufen (auch während Request/Response). */
    @Volatile
    var onBusTelegram: ((Telegram) -> Unit)? = null

    /** User-Befehl (SETPOS/STOP): laufendes Await abbrechen. */
    @Volatile
    private var abortAwait: Boolean = false

    fun requestAbortAwait() {
        abortAwait = true
    }

    fun clearAbortAwait() {
        abortAwait = false
    }

    val isAwaitAborted: Boolean get() = abortAwait

    suspend fun connect(host: String, port: Int) {
        link.connect(host, port)
    }

    suspend fun disconnect() {
        link.disconnect()
    }

    val isConnected: Boolean get() = link.isConnected

    /** Zeit seit dem letzten empfangenen Byte (0 = gerade verbunden/getrennt). */
    val linkSilenceMs: Long get() = link.silenceMs

    suspend fun sendRaw(frame: String): List<Telegram> {
        link.send(frame)
        return readTelegrams(400)
    }

    /** Passiv mitlauschen (SETPOSCC etc. vom zweiten Controller). */
    suspend fun drainBus(waitMs: Long = 60): Int {
        val teles = readTelegrams(waitMs)
        return teles.size
    }

    suspend fun test(dst: Int): Telegram? {
        val frame = Rs485Protocol.build(masterId, dst, "TEST", "0")
        return sendAndAwait(frame, "ACK_TEST", "NAK_TEST")
    }

    suspend fun getPosDg(dst: Int, timeoutMs: Long = 800): Double? {
        val frame = Rs485Protocol.build(masterId, dst, "GETPOSDG", "0")
        val ack = sendAndAwait(
            frame,
            "ACK_GETPOSDG",
            "ACK_POSDG",
            "NAK_GETPOSDG",
            timeoutMs = timeoutMs,
            drainMs = 0,
        ) ?: return null
        if (ack.cmd.startsWith("NAK")) return null
        return Rs485Protocol.parseDegree(ack.params)
    }

    suspend fun setPosDg(dst: Int, deg: Double): Boolean {
        val params = Rs485Protocol.formatDeg(deg)
        val frame = Rs485Protocol.build(masterId, dst, "SETPOSDG", params)
        val ack = sendAndAwait(frame, "ACK_SETPOSDG", "NAK_SETPOSDG")
        return ack != null && ack.cmd.startsWith("ACK") &&
            (Rs485Protocol.parseDegree(ack.params)?.let { it >= 0.5 } ?: true)
    }

    suspend fun stop(dst: Int): Boolean {
        val frame = Rs485Protocol.build(masterId, dst, "STOP", "0")
        val ack = sendAndAwait(frame, "ACK_STOP", "NAK_STOP")
        return ack != null && ack.cmd.startsWith("ACK")
    }

    /** 0 = nicht referenziert, 1 = referenziert; null = Timeout/NAK. */
    suspend fun getRef(dst: Int, timeoutMs: Long = 800): Int? {
        val frame = Rs485Protocol.build(masterId, dst, "GETREF", "0")
        val ack = sendAndAwait(frame, "ACK_GETREF", "NAK_GETREF", timeoutMs = timeoutMs) ?: return null
        if (ack.cmd.startsWith("NAK")) return null
        return Rs485Protocol.parseDegree(ack.params)?.toInt()
    }

    /** Hom-/Parkwinkel in Grad (GETHOMEPOS). */
    suspend fun getHomePos(dst: Int, timeoutMs: Long = 800): Double? {
        val frame = Rs485Protocol.build(masterId, dst, "GETHOMEPOS", "0")
        val ack = sendAndAwait(
            frame,
            "ACK_GETHOMEPOS",
            "NAK_GETHOMEPOS",
            timeoutMs = timeoutMs,
        ) ?: return null
        if (ack.cmd.startsWith("NAK")) return null
        return Rs485Protocol.parseDegree(ack.params)
    }

    /** SETREF:0 = Fehler quittieren, SETREF:1 = Homing starten. */
    suspend fun setRef(dst: Int, value: Int = 1): Boolean {
        val frame = Rs485Protocol.build(masterId, dst, "SETREF", value.toString())
        val ack = sendAndAwait(frame, "ACK_SETREF", "NAK_SETREF", timeoutMs = 1500)
        return ack != null && ack.cmd.startsWith("ACK")
    }

    /** Aktuelles PWM-Limit (%) lesen. */
    suspend fun getPwm(dst: Int): Int? {
        val frame = Rs485Protocol.build(masterId, dst, "GETPWM", "0")
        val ack = sendAndAwait(frame, "ACK_GETPWM", "NAK_GETPWM") ?: return null
        if (ack.cmd.startsWith("NAK")) return null
        return Rs485Protocol.parseDegree(ack.params)?.toInt()
    }

    /**
     * Rotortyp abfragen.
     * 1 = Rotation/Azimut, 2 = Elevation 90°, 3 = Elevation 180°.
     * null = Timeout/NAK.
     */
    suspend fun getRotorType(dst: Int, timeoutMs: Long = 800): Int? {
        val frame = Rs485Protocol.build(masterId, dst, "GETROTORTYPE", "0")
        val ack = sendAndAwait(
            frame,
            "ACK_GETROTORTYPE",
            "NAK_GETROTORTYPE",
            timeoutMs = timeoutMs,
        ) ?: return null
        if (ack.cmd.startsWith("NAK")) return null
        return Rs485Protocol.parseDegree(ack.params)?.toInt()
    }

    /** Warnungen abfragen (`0` oder `id;id;…`). */
    suspend fun getWarn(dst: Int, timeoutMs: Long = 800): List<Int>? {
        val frame = Rs485Protocol.build(masterId, dst, "GETWARN", "0")
        val ack = sendAndAwait(frame, "ACK_GETWARN", "NAK_GETWARN", timeoutMs = timeoutMs)
            ?: return null
        if (ack.cmd.startsWith("NAK")) return null
        return RotorCodes.parseWarnIds(ack.params)
    }

    /** Gelatchten Fehlercode abfragen (`0` = kein Fehler). */
    suspend fun getErr(dst: Int, timeoutMs: Long = 800): Int? {
        val frame = Rs485Protocol.build(masterId, dst, "GETERR", "0")
        val ack = sendAndAwait(frame, "ACK_GETERR", "NAK_GETERR", timeoutMs = timeoutMs)
            ?: return null
        if (ack.cmd.startsWith("NAK")) return null
        return Rs485Protocol.parseDegree(ack.params)?.toInt()
    }

    /** Umgebungstemperatur °C (GETTEMPA). */
    suspend fun getTempA(dst: Int, timeoutMs: Long = 800): Double? {
        val frame = Rs485Protocol.build(masterId, dst, "GETTEMPA", "0")
        val ack = sendAndAwait(frame, "ACK_GETTEMPA", "NAK_GETTEMPA", timeoutMs = timeoutMs)
            ?: return null
        if (ack.cmd.startsWith("NAK")) return null
        return Rs485Protocol.parseDegree(ack.params)
    }

    /** Motortemperatur °C (GETTEMPM); 0 wenn Sensor deaktiviert. */
    suspend fun getTempM(dst: Int, timeoutMs: Long = 800): Double? {
        val frame = Rs485Protocol.build(masterId, dst, "GETTEMPM", "0")
        val ack = sendAndAwait(frame, "ACK_GETTEMPM", "NAK_GETTEMPM", timeoutMs = timeoutMs)
            ?: return null
        if (ack.cmd.startsWith("NAK")) return null
        return Rs485Protocol.parseDegree(ack.params)
    }

    /** PWM-Limit (%) zur Laufzeit setzen (ohne Speichern). */
    suspend fun setPwm(dst: Int, percent: Int): Boolean {
        val p = percent.coerceIn(0, 100)
        val frame = Rs485Protocol.build(masterId, dst, "SETPWM", p.toString())
        val ack = sendAndAwait(frame, "ACK_SETPWM", "NAK_SETPWM")
        return ack != null && ack.cmd.startsWith("ACK")
    }

    private fun antSlot(slot: Int): Int = slot.coerceIn(1, 3)

    /** Antennen-Versatz ° (GETANTOFFn). */
    suspend fun getAntOff(dst: Int, slot: Int, timeoutMs: Long = 800): Double? {
        val n = antSlot(slot)
        val frame = Rs485Protocol.build(masterId, dst, "GETANTOFF$n", "0")
        val ack = sendAndAwait(frame, "ACK_GETANTOFF$n", "NAK_GETANTOFF$n", timeoutMs = timeoutMs)
            ?: return null
        if (ack.cmd.startsWith("NAK")) return null
        return Rs485Protocol.parseDegree(ack.params)
    }

    /** Antennen-Versatz ° setzen (SETANTOFFn). */
    suspend fun setAntOff(dst: Int, slot: Int, deg: Double): Boolean {
        val n = antSlot(slot)
        val params = Rs485Protocol.formatDeg(deg.coerceIn(0.0, 360.0))
        val frame = Rs485Protocol.build(masterId, dst, "SETANTOFF$n", params)
        val ack = sendAndAwait(frame, "ACK_SETANTOFF$n", "NAK_SETANTOFF$n")
        return ack != null && ack.cmd.startsWith("ACK")
    }

    /** Öffnungswinkel ° (GETANGLEn). */
    suspend fun getAntAngle(dst: Int, slot: Int, timeoutMs: Long = 800): Double? {
        val n = antSlot(slot)
        val frame = Rs485Protocol.build(masterId, dst, "GETANGLE$n", "0")
        val ack = sendAndAwait(frame, "ACK_GETANGLE$n", "NAK_GETANGLE$n", timeoutMs = timeoutMs)
            ?: return null
        if (ack.cmd.startsWith("NAK")) return null
        return Rs485Protocol.parseDegree(ack.params)
    }

    /** Öffnungswinkel ° setzen (SETANGLEn). */
    suspend fun setAntAngle(dst: Int, slot: Int, deg: Double): Boolean {
        val n = antSlot(slot)
        val params = Rs485Protocol.formatDeg(deg.coerceIn(0.0, 360.0))
        val frame = Rs485Protocol.build(masterId, dst, "SETANGLE$n", params)
        val ack = sendAndAwait(frame, "ACK_SETANGLE$n", "NAK_SETANGLE$n")
        return ack != null && ack.cmd.startsWith("ACK")
    }

    /** Reichweite km (GETANTDISn). */
    suspend fun getAntDis(dst: Int, slot: Int, timeoutMs: Long = 800): Int? {
        val n = antSlot(slot)
        val frame = Rs485Protocol.build(masterId, dst, "GETANTDIS$n", "0")
        val ack = sendAndAwait(frame, "ACK_GETANTDIS$n", "NAK_GETANTDIS$n", timeoutMs = timeoutMs)
            ?: return null
        if (ack.cmd.startsWith("NAK")) return null
        return Rs485Protocol.parseDegree(ack.params)?.toInt()
    }

    /** Reichweite km setzen (SETANTDISn). */
    suspend fun setAntDis(dst: Int, slot: Int, km: Int): Boolean {
        val n = antSlot(slot)
        val v = km.coerceIn(0, 99_999)
        val frame = Rs485Protocol.build(masterId, dst, "SETANTDIS$n", v.toString())
        val ack = sendAndAwait(frame, "ACK_SETANTDIS$n", "NAK_SETANTDIS$n")
        return ack != null && ack.cmd.startsWith("ACK")
    }

    /** Dipol-Flag 0/1 (GETANTDPn). */
    suspend fun getAntDp(dst: Int, slot: Int, timeoutMs: Long = 800): Boolean? {
        val n = antSlot(slot)
        val frame = Rs485Protocol.build(masterId, dst, "GETANTDP$n", "0")
        val ack = sendAndAwait(frame, "ACK_GETANTDP$n", "NAK_GETANTDP$n", timeoutMs = timeoutMs)
            ?: return null
        if (ack.cmd.startsWith("NAK")) return null
        return (Rs485Protocol.parseDegree(ack.params)?.toInt() ?: 0) != 0
    }

    /** Dipol-Flag setzen (SETANTDPn). */
    suspend fun setAntDp(dst: Int, slot: Int, dipole: Boolean): Boolean {
        val n = antSlot(slot)
        val frame = Rs485Protocol.build(masterId, dst, "SETANTDP$n", if (dipole) "1" else "0")
        val ack = sendAndAwait(frame, "ACK_SETANTDP$n", "NAK_SETANTDP$n")
        return ack != null && ack.cmd.startsWith("ACK")
    }

    /** Anzeigename (GETANTNAMEn); ACK: `NAME;0`. */
    suspend fun getAntName(dst: Int, slot: Int, timeoutMs: Long = 800): String? {
        val n = antSlot(slot)
        val frame = Rs485Protocol.build(masterId, dst, "GETANTNAME$n", "0")
        val ack = sendAndAwait(frame, "ACK_GETANTNAME$n", "NAK_GETANTNAME$n", timeoutMs = timeoutMs)
            ?: return null
        if (ack.cmd.startsWith("NAK")) return null
        return ack.params.substringBefore(';').trim().take(9)
    }

    /** Anzeigename setzen (SETANTNAMEn), max. 9 Zeichen ohne : # $. */
    suspend fun setAntName(dst: Int, slot: Int, name: String): Boolean {
        val n = antSlot(slot)
        val clean = name.trim()
            .replace(":", "")
            .replace("#", "")
            .replace("$", "")
            .take(9)
            .ifEmpty { "Ant$n" }
        val frame = Rs485Protocol.build(masterId, dst, "SETANTNAME$n", clean)
        val ack = sendAndAwait(frame, "ACK_SETANTNAME$n", "NAK_SETANTNAME$n")
        return ack != null && ack.cmd.startsWith("ACK")
    }

    /** Aktuelle Antenne 1–3 vom AZ-Rotor (GETASELECT → NVS). */
    suspend fun getAntennaSelect(azDst: Int, timeoutMs: Long = 800): Int? {
        val frame = Rs485Protocol.build(masterId, azDst, "GETASELECT", "0")
        val ack = sendAndAwait(frame, "ACK_GETASELECT", "NAK_GETASELECT", timeoutMs = timeoutMs)
            ?: return null
        if (ack.cmd.startsWith("NAK")) return null
        val n = Rs485Protocol.parseDegree(ack.params)?.toInt() ?: return null
        return n.coerceIn(1, 3)
    }

    /** Antenne 1–3 am AZ-Rotor speichern (SETASELECT an AZ-Slave). */
    suspend fun setAntennaSelect(azDst: Int, slot: Int, timeoutMs: Long = 800): Boolean {
        val n = antSlot(slot)
        val frame = Rs485Protocol.build(masterId, azDst, "SETASELECT", n.toString())
        val ack = sendAndAwait(frame, "ACK_SETASELECT", "NAK_SETASELECT", timeoutMs = timeoutMs)
        return ack != null && ack.cmd.startsWith("ACK")
    }

    /**
     * ACC-Bins-Block: DIR;START;COUNT → Werte in [0..71].
     * ACK: `DIR;START;COUNT;V1;…;Vn`
     */
    suspend fun getAccBinsBlock(
        dst: Int,
        dir: Int,
        start: Int,
        count: Int,
        timeoutMs: Long = 600,
    ): List<Int>? {
        val d = dir.coerceIn(1, 2)
        val c = count.coerceIn(1, 12)
        val s = start.coerceIn(0, 71)
        val frame = Rs485Protocol.build(masterId, dst, "GETACCBINS", "$d;$s;$c")
        val ack = sendAndAwait(frame, "ACK_GETACCBINS", "NAK_GETACCBINS", timeoutMs = timeoutMs)
            ?: return null
        if (ack.cmd.startsWith("NAK")) return null
        val parts = ack.params.split(';').map { it.trim() }
        if (parts.size < 3 + c) return null
        return (0 until c).map { i ->
            parts[3 + i].replace(',', '.').toDoubleOrNull()?.toInt() ?: 0
        }
    }

    /** Alle 72 ACC-Bins CW (dir=1) und CCW (dir=2) laden. Teilweise OK. */
    suspend fun getAccBinsFull(dst: Int, timeoutMs: Long = 500): Pair<IntArray, IntArray>? {
        val cw = IntArray(72)
        val ccw = IntArray(72)
        var any = false
        for (dir in 1..2) {
            val target = if (dir == 1) cw else ccw
            for (start in listOf(0, 12, 24, 36, 48, 60)) {
                if (abortAwait) return if (any) cw to ccw else null
                val block = getAccBinsBlock(dst, dir, start, 12, timeoutMs = timeoutMs)
                if (block == null) continue
                any = true
                for (i in block.indices) {
                    val j = start + i
                    if (j in 0 until 72) target[j] = block[i]
                }
            }
        }
        return if (any) cw to ccw else null
    }

    suspend fun resetAccBins(dst: Int): Boolean {
        val frame = Rs485Protocol.build(masterId, dst, "SETACCBINSRST", "1")
        val ack = sendAndAwait(frame, "ACK_SETACCBINSRST", "NAK_SETACCBINSRST")
        return ack != null && ack.cmd.startsWith("ACK")
    }

    /** Wind-Sensor aktiv? (GETWINDENABLE). */
    suspend fun getWindEnable(dst: Int, timeoutMs: Long = 800): Boolean? {
        val frame = Rs485Protocol.build(masterId, dst, "GETWINDENABLE", "0")
        val ack = sendAndAwait(
            frame,
            "ACK_GETWINDENABLE",
            "ACK_WINDENABLE",
            "ACK_SETWINDENABLE",
            "NAK_GETWINDENABLE",
            timeoutMs = timeoutMs,
        ) ?: return null
        if (ack.cmd.startsWith("NAK")) return null
        return (Rs485Protocol.parseDegree(ack.params)?.toInt() ?: 0) != 0
    }

    /** Wind-Sensor ein/aus (SETWINDENABLE). */
    suspend fun setWindEnable(dst: Int, enabled: Boolean, timeoutMs: Long = 800): Boolean {
        val frame = Rs485Protocol.build(
            masterId,
            dst,
            "SETWINDENABLE",
            if (enabled) "1" else "0",
        )
        val ack = sendAndAwait(
            frame,
            "ACK_SETWINDENABLE",
            "ACK_GETWINDENABLE",
            "ACK_WINDENABLE",
            "NAK_SETWINDENABLE",
            timeoutMs = timeoutMs,
        ) ?: return false
        return ack.cmd.startsWith("ACK")
    }

    /** Windgeschwindigkeit km/h (GETANEMO). */
    suspend fun getAnemo(dst: Int, timeoutMs: Long = 800): Double? {
        val frame = Rs485Protocol.build(masterId, dst, "GETANEMO", "0")
        val ack = sendAndAwait(frame, "ACK_GETANEMO", "NAK_GETANEMO", timeoutMs = timeoutMs)
            ?: return null
        if (ack.cmd.startsWith("NAK")) return null
        return Rs485Protocol.parseDegree(ack.params)
    }

    /** Windrichtung ° (GETWINDDIR). */
    suspend fun getWindDir(dst: Int, timeoutMs: Long = 800): Double? {
        val frame = Rs485Protocol.build(masterId, dst, "GETWINDDIR", "0")
        val ack = sendAndAwait(
            frame,
            "ACK_GETWINDDIR",
            "ACK_WINDDIR",
            "NAK_GETWINDDIR",
            "NAK_WINDDIR",
            timeoutMs = timeoutMs,
        ) ?: return null
        if (ack.cmd.startsWith("NAK")) return null
        return Rs485Protocol.parseDegree(ack.params)
    }

    private suspend fun sendAndAwait(
        frame: String,
        vararg expectCmd: String,
        timeoutMs: Long = 800,
        drainMs: Long = 5,
    ): Telegram? {
        // Minimal drain — 50 ms Pause zwischen Befehlen ist unnötig, ACK ist schon da
        if (abortAwait) return null
        if (drainMs > 0) readTelegrams(drainMs)
        if (abortAwait) return null
        link.send(frame)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (abortAwait) return null
            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(5)
            val teles = readTelegrams(remaining.coerceAtMost(40))
            for (t in teles) {
                if (!t.ok && !isSniffCmd(t.cmd)) continue
                if (t.dst != masterId &&
                    t.dst != Rs485Protocol.BROADCAST_DST &&
                    !isSniffCmd(t.cmd)
                ) {
                    continue
                }
                if (expectCmd.any {
                        t.cmd.equals(it, ignoreCase = true) ||
                            t.cmd.startsWith(it, ignoreCase = true)
                    }
                ) {
                    return t
                }
            }
            delay(2)
        }
        return null
    }

    private fun isSniffCmd(cmd: String): Boolean {
        val u = cmd.uppercase()
        return u == "SETPOSCC" ||
            u == "SETPOSDG" ||
            u == "SETASELECT" ||
            u == "SETPWM" ||
            u == "STOP" ||
            u == "SETREF" ||
            u.startsWith("ACK_SETREF") ||
            u == "ERR" ||
            u == "WARN" ||
            u.startsWith("ACK_GETPOSDG") ||
            u.startsWith("ACK_POSDG") ||
            u.startsWith("ACK_SETPOSDG") ||
            u.startsWith("ACK_SETPWM") ||
            u.startsWith("ACK_GETPWM") ||
            u.startsWith("ACK_GETWARN") ||
            u.startsWith("ACK_GETERR") ||
            u.startsWith("ACK_ERR") ||
            u.startsWith("ACK_GETASELECT") ||
            u.startsWith("ACK_SETASELECT")
    }

    private suspend fun readTelegrams(waitMs: Long): List<Telegram> {
        val frames = link.readFrames(waitMs)
        val teles = frames.mapNotNull { Rs485Protocol.parse(it) }
        val cb = onBusTelegram
        if (cb != null) {
            for (t in teles) {
                runCatching { cb(t) }
            }
        }
        return teles
    }
}
