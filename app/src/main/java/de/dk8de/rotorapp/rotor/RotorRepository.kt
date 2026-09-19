package de.dk8de.rotorapp.rotor

import android.os.SystemClock
import de.dk8de.rotorapp.data.RotorProfile
import de.dk8de.rotorapp.net.TcpLink
import de.dk8de.rotorapp.ui.components.AngleSmoother
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

enum class RotorAxis { AZ, EL }

/** Eine der drei Antennen-Slots am AZ-Rotor (NVS). */
data class AntennaSlot(
    val offsetDeg: Double = 0.0,
    val openingDeg: Double = 0.0,
    val rangeKm: Int = 100,
    val dipole: Boolean = false,
    val name: String = "",
) {
    fun label(slot: Int): String {
        val n = name.trim().ifEmpty { "Antenne $slot" }
        val off = offsetDeg.roundTo1()
        return "$n (${off}°)"
    }
}

private fun Double.roundTo1(): String =
    if (kotlin.math.abs(this - this.toInt()) < 0.05) toInt().toString()
    else "%.1f".format(this)

data class RotorLiveState(
    val connected: Boolean = false,
    val statusText: String = "Getrennt",
    val lastLog: String = "",
    /** Rohposition von GETPOSDG (Logik/Zielvergleich). */
    val azDeg: Double? = null,
    val elDeg: Double? = null,
    /** Geglättete Anzeige wie Bridge-Kompass (Segment-Lerp + SmoothDamp). */
    val azSmoothDeg: Double? = null,
    val elSmoothDeg: Double? = null,
    /** Motorziel (eigenes SETPOSDG oder Bus-SETPOSDG). */
    val azTarget: Double? = null,
    val elTarget: Double? = null,
    /** Kompass-Soll vom zweiten Controller (SETPOSCC), nur Anzeige. */
    val azCompassTarget: Double? = null,
    val elCompassTarget: Double? = null,
    /** Slave antwortet auf dem Bus. */
    val azOnline: Boolean = false,
    val elOnline: Boolean = false,
    /**
     * Früher: anderer Master pollt → App nur mitsniffen.
     * Fernbedienung (SETPOSCC/SETPOSDG) lässt die App aktiv pollen; Flag bleibt für Kompatibilität.
     */
    val busPassive: Boolean = false,
    val moving: Boolean = false,
    val azReferenced: Boolean = false,
    val elReferenced: Boolean = false,
    val azHoming: Boolean = false,
    val elHoming: Boolean = false,
    val pwmAz: Int? = null,
    val pwmEl: Int? = null,
    /** Umgebungstemperatur °C (GETTEMPA). */
    val tempAmbientC: Double? = null,
    /** Motortemperatur AZ °C (GETTEMPM). */
    val tempMotorAzC: Double? = null,
    /** Motortemperatur EL °C (GETTEMPM), nur bei enableEl. */
    val tempMotorElC: Double? = null,
    /**
     * GETROTORTYPE: 1=AZ, 2=EL90, 3=EL180.
     * Ohne Antwort: AZ=1, EL=2 (90°).
     */
    val azRotorType: Int = 1,
    val elRotorType: Int = 2,
    /** Max. Elevationwinkel (90 oder 180) für Anzeige/Eingabe. */
    val elMaxDeg: Double = 90.0,
    /** Gelatchter Fehlercode (Broadcast ERR / ACK_GETERR), 0 = keiner. */
    val azErrorCode: Int = 0,
    val elErrorCode: Int = 0,
    /** Aktive Warnungs-IDs (GETWARN / Bus). */
    val azWarnIds: List<Int> = emptyList(),
    val elWarnIds: List<Int> = emptyList(),
    /** Antennen 1–3 (AZ-Rotor). */
    val antennas: List<AntennaSlot> = listOf(AntennaSlot(), AntennaSlot(), AntennaSlot()),
    /** Gewählte Antenne 1–3 (GETASELECT / SETASELECT). */
    val selectedAntenna: Int = 1,
    /**
     * Bei Dipol: Soll-Peilung für die Anzeige (angetippte Richtung),
     * unabhängig von der gewählten Rotor-Keule (+0°/+180°).
     */
    val azDipoleDisplayBearing: Double? = null,
    /**
     * Stromverbrauch-Heatmap: 36 Bins (10°), max(CW,CCW) aus Firmware GETACCBINS.
     * null = noch nicht geladen.
     */
    val stromBins36: List<Int>? = null,
    /** Stromverbrauch-Heatmap EL: 36 Bins über 90°/180°. null = nicht geladen. */
    val stromBinsEl36: List<Int>? = null,
    /** Standzeit je Sektor (konfigurierbare Anzahl), Rotor-Koordinaten. */
    val dwellSeconds: List<Float> = List(20) { 0f },
    /** Wind km/h (nur wenn Profil enableWind). */
    val windKmh: Double? = null,
    /** Windrichtung ° (von wo der Wind kommt). */
    val windDirDeg: Double? = null,
    val windHwEnabled: Boolean = false,
) {
    val referenced: Boolean get() = azReferenced && elReferenced
    val homing: Boolean get() = azHoming || elHoming
    /** Motor-/Bus-Soll (Rotorwinkel). */
    val azSollDeg: Double? get() = azCompassTarget ?: azTarget
    val elSollDeg: Double? get() = elCompassTarget ?: elTarget
    val elIs180: Boolean get() = elMaxDeg >= 179.5
    val hasFault: Boolean get() = azErrorCode != 0 || elErrorCode != 0
    val hasWarning: Boolean get() = azWarnIds.isNotEmpty() || elWarnIds.isNotEmpty()

    val selectedOffsetDeg: Double
        get() = antennas.getOrNull(selectedAntenna.coerceIn(1, 3) - 1)?.offsetDeg ?: 0.0

    val selectedDipole: Boolean
        get() = antennas.getOrNull(selectedAntenna.coerceIn(1, 3) - 1)?.dipole == true

    /** Rotorstellung → Anzeigepeilung (Versatz addieren). */
    fun displayAz(rotorDeg: Double?): Double? =
        rotorDeg?.let { AntennaMath.displayFromRotor(it, selectedOffsetDeg) }

    /**
     * Soll-Peilung für Kompass/Text: bei Dipol die angetippte Richtung,
     * sonst Rotor-Soll + Versatz.
     */
    fun azSollDisplay(): Double? =
        azDipoleDisplayBearing ?: displayAz(azSollDeg)

    /** Anzeigepeilung → Rotorstellung (Versatz abziehen, ohne Dipol-Wahl). */
    fun rotorAzFromDisplay(displayDeg: Double): Double =
        AntennaMath.rotorFromDisplay(displayDeg, selectedOffsetDeg)
}

object AntennaMath {
    const val N_BINS_36 = 36
    const val ACC_BINS_72 = 72

    fun wrap360(deg: Double): Double {
        var d = deg % 360.0
        if (d < 0) d += 360.0
        return d
    }

    fun displayFromRotor(rotorDeg: Double, offsetDeg: Double): Double =
        wrap360(rotorDeg + offsetDeg)

    fun rotorFromDisplay(displayDeg: Double, offsetDeg: Double): Double =
        wrap360(displayDeg - offsetDeg)

    /** 72 ACC-Bins CW/CCW → 36 Anzeige-Bins (je 10°, max der beiden Richtungen + Paar). */
    fun mergeAccTo36(cw: IntArray, ccw: IntArray): List<Int> {
        val raw = IntArray(ACC_BINS_72) { i ->
            val a = if (i < cw.size) cw[i] else 0
            val b = if (i < ccw.size) ccw[i] else 0
            maxOf(a, b)
        }
        return (0 until N_BINS_36).map { j ->
            maxOf(raw[j * 2], raw[j * 2 + 1])
        }
    }

    /**
     * EL: Firmware 36 Bins über den Hub; max(CW,CCW) je Index 0..35
     * (wie Bridge EL_N_SEG).
     */
    fun mergeAccEl36(cw: IntArray, ccw: IntArray): List<Int> =
        (0 until N_BINS_36).map { i ->
            val a = if (i < cw.size) cw[i] else 0
            val b = if (i < ccw.size) ccw[i] else 0
            maxOf(a, b)
        }

    fun sectorIndex36(rotorDeg: Double): Int = sectorIndex(rotorDeg, N_BINS_36)

    fun sectorIndex(rotorDeg: Double, nSectors: Int): Int {
        val n = nSectors.coerceIn(10, 100)
        val step = 360.0 / n
        return ((wrap360(rotorDeg) / step).toInt()).coerceIn(0, n - 1)
    }

    private fun cwTravel(cur: Double, tgt: Double): Double =
        (wrap360(tgt) - wrap360(cur) + 360.0) % 360.0

    private fun ccwTravel(cur: Double, tgt: Double): Double =
        (wrap360(cur) - wrap360(tgt) + 360.0) % 360.0

    private fun normalizeForRouting(cur: Double): Double =
        if (cur >= 359.95) 360.0 else wrap360(cur)

    /**
     * Geschätzter Rotor-Fahrweg (wie Bridge dipole_rotor_move_cost),
     * inkl. typischem Nullpunkt-Überfahren des Reglers.
     */
    fun dipoleMoveCost(cur: Double, tgt: Double): Double {
        var c = cur
        val t = wrap360(tgt)
        c = if (c >= 359.95) 360.0 else wrap360(c)
        val cw = cwTravel(c, t)
        val ccw = ccwTravel(c, t)
        if (kotlin.math.abs(cw - ccw) < 1e-9) return cw
        // Ziel kleiner als Ist: oft langer CCW-Bogen
        if (t < c && cw < ccw && ccw > 180.0) return ccw
        // Ziel größer als Ist: oft langer CW-Bogen
        if (t > c && cw > ccw && cw > 180.0) return cw
        return minOf(cw, ccw)
    }

    /**
     * Rotor-Azimut für Ziel-Peilung (Anzeige).
     * Dipol: Haupt- oder Gegenkeule (+180°) — kürzerer Fahrweg vom Ist.
     */
    fun rotorAzForDisplayBearing(
        displayBearingDeg: Double,
        offsetDeg: Double,
        currentRotorAz: Double?,
        dipole: Boolean,
        lastRotorAz: Double? = null,
    ): Double {
        val primary = wrap360(displayBearingDeg - offsetDeg)
        if (!dipole) return primary

        val alternate = wrap360(primary + 180.0)
        if (currentRotorAz == null) return primary

        val cur = normalizeForRouting(currentRotorAz)
        var costP = dipoleMoveCost(cur, primary)
        var costA = dipoleMoveCost(cur, alternate)

        if (lastRotorAz != null) {
            val last = normalizeForRouting(lastRotorAz)
            val onPrimary = dipoleMoveCost(last, primary) <= 25.0
            val onAlternate = dipoleMoveCost(last, alternate) <= 25.0
            when {
                onAlternate && !onPrimary -> costP += 30.0
                onPrimary && !onAlternate -> costA += 30.0
                kotlin.math.abs(costA - costP) <= 30.0 -> {
                    val dLastA = dipoleMoveCost(last, alternate)
                    val dLastP = dipoleMoveCost(last, primary)
                    when {
                        dLastA < dLastP -> costP += 10.0
                        dLastP < dLastA -> costA += 10.0
                    }
                }
            }
        }

        return if (costA < costP) alternate else primary
    }
}

class RotorRepository(context: android.content.Context) {
    private val link = TcpLink(context.applicationContext)
    private val client = RotorClient(link).also { c ->
        c.onBusTelegram = { tel -> onBusTelegram(tel) }
    }
    private val ioMutex = Mutex()

    private val azSmoother = AngleSmoother(wrap360 = true)
    private val elSmoother = AngleSmoother(wrap360 = false)
    private val _state = MutableStateFlow(RotorLiveState())
    val state: StateFlow<RotorLiveState> = _state.asStateFlow()

    private var profile: RotorProfile = RotorProfile()
    private var lastHost: String = profile.host
    private var lastPort: Int = profile.port

    /** Wenn GETROTORTYPE einen neuen EL-Typ liefert: Profil-Cache speichern. */
    var onElRotorTypeCached: ((RotorProfile) -> Unit)? = null
    /** Wind enable aus GETWINDENABLE → Profil/Haken anpassen. */
    var onWindEnableSynced: ((RotorProfile) -> Unit)? = null

    /** Nach Profil-Speichern: SETWINDENABLE beim nächsten Connect (sonst nur GET). */
    private var pendingWindEnableWrite: Boolean? = null

    fun requestWindEnableWrite(enabled: Boolean) {
        pendingWindEnableWrite = enabled
    }

    /** Kurz nach eigenem SETPOSDG: SETPOSCC vom Encoder ignorieren (Bridge 0,6 s). */
    private var azIgnoreCcUntilMs: Long = 0L
    private var elIgnoreCcUntilMs: Long = 0L
    private var azFailStreak: Int = 0
    private var elFailStreak: Int = 0
    private var nextAzProbeMs: Long = 0L
    private var nextElProbeMs: Long = 0L
    /** Letzte Dipol-Rotorstellung für Keulen-Hysterese (wie Bridge). */
    private var azDipoleLastRotorAz: Double? = null
    private var dwellLastTickMs: Long = 0L
    private var lastAccFetchMs: Long = 0L
    private var lastWindFetchMs: Long = 0L
    /** Keepalive der ruhenden Online-Achse während die andere fährt. */
    private var lastAzKeepaliveMs: Long = 0L
    private var lastElKeepaliveMs: Long = 0L
    /** Verhindert doppeltes Full-Bootstrap (Profil-Collect + onForeground). */
    private var lastBootstrapMs: Long = 0L
    private var wantStromRing: Boolean = true
    private var wantDwellRing: Boolean = true
    private var dwellSectorCount: Int = 20
    private var wantWindPoll: Boolean = false
    @Volatile
    var lastOkPollMs: Long = 0L
        private set

    /**
     * User-Touch (SETPOS/STOP): laufende Polls/Awaits abbrechen.
     * Bleibt gesetzt, bis [endUserCommand] — Hintergrund-Polls mit tryLock weichen.
     */
    @Volatile
    private var userCmdPending: Boolean = false

    /** Nach SETPOSDG: Idle-Zusatz (Temp/ACC/Wind/Ref) kurz pausieren — Bus frei für Fahrt. */
    private var idleExtrasDeferUntilMs: Long = 0L

    /**
     * Nach SETPOS: zwingend Fast-Poll der Online-Achse (auch wenn ERR moving löscht /
     * Idle-Zweig noch in delay steckt). Verhindert Deadman durch GETREF auf Offline-Slave.
     */
    @Volatile
    private var motionHoldUntilMs: Long = 0L

    /** Touch/GO: Polling abbrechen, Bus sofort für SETPOS freigeben. */
    fun beginUserCommand() {
        userCmdPending = true
        client.requestAbortAwait()
    }

    fun endUserCommand() {
        client.clearAbortAwait()
        userCmdPending = false
    }

    fun isMotionHoldActive(): Boolean =
        SystemClock.elapsedRealtime() < motionHoldUntilMs

    /** Fast-Poll nötig: Fahrt, Homing oder kurz nach SETPOS. */
    fun needsFastPoll(): Boolean {
        val s = _state.value
        return s.moving || s.homing || isMotionHoldActive()
    }

    private fun idleExtrasDeferred(): Boolean =
        SystemClock.elapsedRealtime() < idleExtrasDeferUntilMs

    private fun armSetPosPollGrace() {
        val now = SystemClock.elapsedRealtime()
        idleExtrasDeferUntilMs = now + SETPOS_IDLE_GRACE_MS
        motionHoldUntilMs = now + SETPOS_MOTION_HOLD_MS
    }

    fun setDisplayRingPrefs(strom: Boolean, dwell: Boolean) {
        wantStromRing = strom
        wantDwellRing = dwell
    }

    fun setDwellSectorCount(n: Int) {
        val next = n.coerceIn(10, 100)
        if (next == dwellSectorCount) return
        dwellSectorCount = next
        dwellLastTickMs = SystemClock.elapsedRealtime()
        _state.value = _state.value.copy(
            dwellSeconds = List(dwellSectorCount) { 0f },
        )
    }

    fun setWindPollEnabled(enabled: Boolean) {
        wantWindPoll = enabled
        if (!enabled) {
            _state.value = _state.value.copy(
                windKmh = null,
                windDirDeg = null,
                windHwEnabled = false,
            )
        }
    }

    /**
     * Beim Connect: optional SETWINDENABLE (nach Profil-Speichern), sonst GETWINDENABLE
     * und Profil-Haken an den Rotor-Zustand anpassen.
     */
    private suspend fun syncWindEnableOnConnectLocked() {
        if (!client.isConnected) return
        val dst = profile.slaveAz
        val pending = pendingWindEnableWrite
        pendingWindEnableWrite = null

        val en: Boolean? = if (pending != null) {
            val ok = runCatching {
                client.setWindEnable(dst, pending, timeoutMs = 500)
            }.getOrDefault(false)
            if (ok) {
                pending
            } else {
                runCatching { client.getWindEnable(dst, timeoutMs = 500) }.getOrNull()
            }
        } else {
            runCatching { client.getWindEnable(dst, timeoutMs = 500) }.getOrNull()
        }

        if (en == null) {
            _state.value = _state.value.copy(lastLog = "GETWINDENABLE ?")
            return
        }
        applySyncedWindEnable(en, logPrefix = if (pending != null) "SETWINDENABLE" else "GETWINDENABLE")
    }

    private fun applySyncedWindEnable(enabled: Boolean, logPrefix: String) {
        wantWindPoll = enabled
        _state.value = _state.value.copy(
            windHwEnabled = enabled,
            windKmh = if (enabled) _state.value.windKmh else null,
            windDirDeg = if (enabled) _state.value.windDirDeg else null,
            lastLog = "$logPrefix ${if (enabled) 1 else 0}",
        )
        if (profile.enableWind != enabled) {
            profile = profile.copy(enableWind = enabled)
            onWindEnableSynced?.invoke(profile)
        }
    }

    companion object {
        private const val SETPOSCC_SUPPRESS_MS = 600L
        private const val POS_TIMEOUT_ONLINE_MS = 700L
        private const val POS_TIMEOUT_OFFLINE_MS = 220L
        /** Während Fahrt: kürzerer GETPOS-Timeout (ACK kommt meist <100 ms). */
        private const val POS_TIMEOUT_FAST_MS = 350L
        /** Offline-Achse: GETREF-Probe-Intervall. */
        private const val OFFLINE_RETRY_MS = 5_000L
        /** Ruhende Online-Achse während Fahrt der anderen: Keepalive. */
        private const val SIBLING_KEEPALIVE_MS = 1_000L
        private const val SIBLING_KEEPALIVE_TIMEOUT_MS = 300L
        /** Wie Bridge _SETPOSDG_POLL_GRACE_S: Idle-Zusatz nach SETPOS pausieren. */
        private const val SETPOS_IDLE_GRACE_MS = 1_500L
        /** Nach SETPOS: Fast-GETPOS erzwingen (Deadman), auch wenn moving kurz false. */
        private const val SETPOS_MOTION_HOLD_MS = 2_500L
        private const val FAIL_STREAK_OFFLINE = 2
    }

    fun activeProfile(): RotorProfile = profile

    suspend fun applyProfile(p: RotorProfile, reconnect: Boolean) {
        val wasConnected = client.isConnected
        if (wasConnected) {
            runCatching { client.disconnect() }
        }
        profile = p
        client.masterId = p.masterId
        azSmoother.clear()
        elSmoother.clear()
        resetAxisHealth()
        _state.value = _state.value.copy(
            connected = false,
            statusText = "",
            azDeg = null,
            elDeg = null,
            azSmoothDeg = null,
            elSmoothDeg = null,
            azTarget = null,
            elTarget = null,
            azCompassTarget = null,
            elCompassTarget = null,
            azOnline = false,
            elOnline = false,
            busPassive = false,
            moving = false,
            azReferenced = false,
            elReferenced = !p.enableEl,
            azHoming = false,
            elHoming = false,
            pwmAz = null,
            pwmEl = null,
            tempAmbientC = null,
            tempMotorAzC = null,
            tempMotorElC = null,
            azRotorType = 1,
            elRotorType = p.cachedElRotorType(),
            elMaxDeg = p.cachedElMaxDeg(),
            azErrorCode = 0,
            elErrorCode = 0,
            azWarnIds = emptyList(),
            elWarnIds = emptyList(),
            antennas = listOf(AntennaSlot(), AntennaSlot(), AntennaSlot()),
            selectedAntenna = 1,
            azDipoleDisplayBearing = null,
            stromBins36 = null,
            stromBinsEl36 = null,
        )
        azDipoleLastRotorAz = null
        lastAccFetchMs = 0L
        if (reconnect || wasConnected) {
            connect()
        }
    }

    suspend fun connect(
        host: String? = null,
        port: Int? = null,
        quietFail: Boolean = false,
    ): Boolean = ioMutex.withLock {
        val h = host ?: profile.host
        val p = port ?: profile.port
        lastHost = h
        lastPort = p
        return try {
            client.connect(h, p)
            resetAxisHealth()
            // Verhindert, dass onForeground den frischen Connect sofort als „stale“ killt
            lastOkPollMs = System.currentTimeMillis()
            _state.value = _state.value.copy(
                connected = true,
                statusText = "",
                lastLog = "TCP OK",
                azOnline = false,
                elOnline = false,
                busPassive = false,
                azReferenced = false,
                elReferenced = !profile.enableEl,
                azHoming = false,
                elHoming = false,
                azRotorType = 1,
                elRotorType = profile.cachedElRotorType(),
                elMaxDeg = profile.cachedElMaxDeg(),
                azErrorCode = 0,
                elErrorCode = 0,
                azWarnIds = emptyList(),
                elWarnIds = emptyList(),
            )
            // Doppel-Connect (Collect + Lifecycle) → schweres Bootstrap nur einmal,
            // Ref+Position aber IMMER (sonst Nadel erst nach Idle-Probe, Temps bleiben aus Cache)
            val nowBoot = SystemClock.elapsedRealtime()
            val skipHeavyBootstrap = nowBoot - lastBootstrapMs < 2_500L
            runCatching { refreshReferenceLocked() }
            runCatching { fetchStartupPositionsLocked() }
            // GETPOS-Retry falls erster Versuch leer blieb (Bus noch warm)
            if (_state.value.azOnline && _state.value.azDeg == null ||
                (profile.enableEl && _state.value.elOnline && _state.value.elDeg == null)
            ) {
                runCatching { fetchStartupPositionsLocked() }
            }
            if (!skipHeavyBootstrap) {
                lastBootstrapMs = nowBoot
                runCatching { queryRotorTypesLocked() }
                runCatching { refreshPwmLocked() }
                runCatching { refreshWarningsLocked() }
                runCatching { refreshTempsLocked() }
            }
            // Wind: GETWINDENABLE → Haken; SET nur wenn Profil gerade Wind geändert hat
            runCatching { syncWindEnableOnConnectLocked() }
            runCatching { refreshWindLocked(force = true) }
            true
        } catch (e: Exception) {
            runCatching { client.disconnect() }
            _state.value = _state.value.copy(
                connected = false,
                statusText = if (quietFail) "" else "Verbindung fehlgeschlagen",
                lastLog = e.message ?: e.toString(),
                azOnline = false,
                elOnline = false,
                busPassive = false,
                azReferenced = false,
                elReferenced = !profile.enableEl,
                azHoming = false,
                elHoming = false,
                azRotorType = 1,
                elRotorType = profile.cachedElRotorType(),
                elMaxDeg = profile.cachedElMaxDeg(),
                azErrorCode = 0,
                elErrorCode = 0,
                azWarnIds = emptyList(),
                elWarnIds = emptyList(),
                tempAmbientC = null,
                tempMotorAzC = null,
                tempMotorElC = null,
            )
            false
        }
    }

    suspend fun disconnect() = ioMutex.withLock {
        client.disconnect()
        azSmoother.clear()
        elSmoother.clear()
        resetAxisHealth()
        _state.value = _state.value.copy(
            connected = false,
            statusText = "",
            moving = false,
            busPassive = false,
            azReferenced = false,
            elReferenced = !profile.enableEl,
            azHoming = false,
            elHoming = false,
            azOnline = false,
            elOnline = false,
            azDeg = null,
            elDeg = null,
            azSmoothDeg = null,
            elSmoothDeg = null,
            azTarget = null,
            elTarget = null,
            azCompassTarget = null,
            elCompassTarget = null,
            pwmAz = null,
            pwmEl = null,
            tempAmbientC = null,
            tempMotorAzC = null,
            tempMotorElC = null,
            azRotorType = 1,
            elRotorType = profile.cachedElRotorType(),
            elMaxDeg = profile.cachedElMaxDeg(),
            azErrorCode = 0,
            elErrorCode = 0,
            azWarnIds = emptyList(),
            elWarnIds = emptyList(),
            stromBins36 = null,
            stromBinsEl36 = null,
        )
        lastAccFetchMs = 0L
    }

    /**
     * Nach langem Hintergrund: TCP oft tot, obwohl Flag noch true.
     * Bei Disconnect oder länger ohne erfolgreichen Poll neu verbinden.
     * Frisch verbunden ohne Poll (lastOkPollMs==0) gilt nicht als stale —
     * sonst killt onForeground den Connect während des Startup-Reads.
     */
    suspend fun ensureConnectedOrReconnect(staleAfterMs: Long = 15_000L): Boolean {
        val now = System.currentTimeMillis()
        if (client.isConnected && _state.value.connected) {
            val neverPolled = lastOkPollMs == 0L
            val fresh = !neverPolled && now - lastOkPollMs <= staleAfterMs
            if (neverPolled || fresh) return true
        }
        if (client.isConnected) {
            runCatching { disconnect() }
        }
        return connect(lastHost.ifBlank { profile.host }, lastPort, quietFail = true)
    }
    /**
     * Render-Tick wie Bridge-Kompass (~33 ms): SmoothDamp/Segment-Lerp fortschreiben.
     * Standzeit-Ring: bei Stillstand Sektor akkumulieren.
     */
    fun tickDisplay() {
        val now = SystemClock.elapsedRealtime()
        val az = azSmoother.tick(now)?.toDouble()
        val el = if (profile.enableEl) elSmoother.tick(now)?.toDouble() else null
        val prev = _state.value

        var dwell = prev.dwellSeconds
        val nSec = dwellSectorCount.coerceIn(10, 100)
        if (dwell.size != nSec) {
            dwell = List(nSec) { 0f }
        }
        if (wantDwellRing && !prev.moving && prev.azDeg != null && prev.azOnline) {
            if (dwellLastTickMs > 0L) {
                val dt = ((now - dwellLastTickMs).coerceIn(0L, 200L)) / 1000f
                if (dt > 0f) {
                    val idx = AntennaMath.sectorIndex(prev.azDeg, nSec)
                    val next = dwell.toMutableList()
                    next[idx] = next[idx] + dt
                    if (prev.selectedDipole) {
                        val idx2 = AntennaMath.sectorIndex(prev.azDeg + 180.0, nSec)
                        next[idx2] = next[idx2] + dt
                    }
                    dwell = next
                }
            }
            dwellLastTickMs = now
        } else {
            dwellLastTickMs = now
        }

        if (az == prev.azSmoothDeg && el == prev.elSmoothDeg && dwell === prev.dwellSeconds) return
        _state.value = prev.copy(azSmoothDeg = az, elSmoothDeg = el, dwellSeconds = dwell)
    }

    fun resetDwellTimes() {
        dwellLastTickMs = SystemClock.elapsedRealtime()
        _state.value = _state.value.copy(
            dwellSeconds = List(dwellSectorCount.coerceIn(10, 100)) { 0f },
            lastLog = "Standzeit zurückgesetzt",
        )
    }

    suspend fun refreshAccBins(force: Boolean = false) {
        if (!client.isConnected || !wantStromRing) return
        if (userCmdPending || (!force && idleExtrasDeferred())) return
        val s = _state.value
        if (s.moving || s.homing) return
        val azOk = s.azOnline
        val elOk = profile.enableEl && s.elOnline
        if (!azOk && !elOk) return
        if (!ioMutex.tryLock()) return
        try {
            if (userCmdPending) return
            val now = System.currentTimeMillis()
            val haveAz = !azOk || _state.value.stromBins36 != null
            val haveEl = !elOk || _state.value.stromBinsEl36 != null
            if (!force && now - lastAccFetchMs < 4_000L && haveAz && haveEl) {
                return
            }
            lastAccFetchMs = now
            // Nur Online-Achsen — Offline blockiert sonst den Bus (Deadman der fahrenden Achse)
            val binsAz = if (azOk) {
                runCatching {
                    client.getAccBinsFull(profile.slaveAz, timeoutMs = 450)
                }.getOrNull()?.let { AntennaMath.mergeAccTo36(it.first, it.second) }
            } else {
                null
            }
            if (userCmdPending || client.isAwaitAborted) return
            val binsEl = if (elOk) {
                runCatching {
                    client.getAccBinsFull(profile.slaveEl, timeoutMs = 450)
                }.getOrNull()?.let { AntennaMath.mergeAccEl36(it.first, it.second) }
            } else {
                null
            }
            if (userCmdPending) return
            if (binsAz == null && binsEl == null) {
                if (azOk || elOk) {
                    _state.value = _state.value.copy(lastLog = "GETACCBINS fehlgeschlagen")
                }
                return
            }
            _state.value = _state.value.copy(
                stromBins36 = binsAz ?: _state.value.stromBins36,
                stromBinsEl36 = binsEl ?: _state.value.stromBinsEl36,
                lastLog = buildString {
                    append("GETACCBINS")
                    if (binsAz != null) append(" AZ ${binsAz.count { it > 0 }}/36")
                    if (binsEl != null) append(" EL ${binsEl.count { it > 0 }}/36")
                },
            )
        } finally {
            ioMutex.unlock()
        }
    }

    suspend fun resetAccBins() = ioMutex.withLock {
        if (!client.isConnected) return@withLock
        val s = _state.value
        val okAz = if (s.azOnline) {
            runCatching { client.resetAccBins(profile.slaveAz) }.getOrDefault(false)
        } else {
            false
        }
        val okEl = if (profile.enableEl && s.elOnline) {
            runCatching { client.resetAccBins(profile.slaveEl) }.getOrDefault(false)
        } else {
            !profile.enableEl
        }
        if (okAz || okEl) {
            lastAccFetchMs = 0L
            _state.value = _state.value.copy(
                stromBins36 = if (okAz) List(AntennaMath.N_BINS_36) { 0 } else _state.value.stromBins36,
                stromBinsEl36 = if (okEl && profile.enableEl) {
                    List(AntennaMath.N_BINS_36) { 0 }
                } else {
                    _state.value.stromBinsEl36
                },
                lastLog = "ACC-Bins zurückgesetzt",
            )
        }
    }

    suspend fun refreshWind(force: Boolean = false): Boolean {
        if (!client.isConnected || !wantWindPoll) return false
        if (!force && (userCmdPending || idleExtrasDeferred())) return false
        // Wind hängt am AZ — offline nicht anfragen
        if (!_state.value.azOnline) return false
        if (!_forceIdleOk(force)) return false
        // Warten statt tryLock: sonst fallen Idle-Abfragen gegen GETPOS oft aus (5–6 s Lücken)
        return ioMutex.withLock {
            if (!force && (userCmdPending || !_state.value.azOnline)) return@withLock false
            if (!_forceIdleOk(force)) return@withLock false
            refreshWindLocked(force = force)
            true
        }
    }

    private fun _forceIdleOk(force: Boolean): Boolean {
        if (force) return true
        val s = _state.value
        return !s.moving && !s.homing && !isMotionHoldActive()
    }

    private suspend fun refreshWindLocked(force: Boolean = false) {
        if (!wantWindPoll) return
        if (!_state.value.azOnline) return
        val now = System.currentTimeMillis()
        if (!force && now - lastWindFetchMs < 2_000L) return
        lastWindFetchMs = now
        val dst = profile.slaveAz
        val en = runCatching { client.getWindEnable(dst, timeoutMs = 500) }.getOrNull()
        if (!force && (userCmdPending || client.isAwaitAborted)) return
        if (en != true) {
            _state.value = _state.value.copy(
                windHwEnabled = en == true,
                windKmh = null,
                windDirDeg = null,
                lastLog = if (en == false) "Wind HW aus" else "GETWINDENABLE ?",
            )
            return
        }
        val kmh = runCatching { client.getAnemo(dst, timeoutMs = 500) }.getOrNull()
        if (!force && (userCmdPending || client.isAwaitAborted)) return
        val dir = runCatching { client.getWindDir(dst, timeoutMs = 500) }.getOrNull()
        if (!force && userCmdPending) return
        _state.value = _state.value.copy(
            windHwEnabled = true,
            windKmh = kmh,
            windDirDeg = dir?.let { AntennaMath.wrap360(it) },
            lastLog = buildString {
                append("Wind ")
                append(kmh?.let { "%.1f km/h".format(it) } ?: "?")
                append(" · ")
                append(dir?.let { "%.0f°".format(it) } ?: "?")
            },
        )
    }

    /** Passiv Bus mitlauschen (SETPOSCC / fremdes SETPOSDG / Positions-ACKs). */
    suspend fun sniffBus(waitMs: Long = 40) {
        if (!client.isConnected) return
        // Während Fast-Poll: Sniff nicht den Bus-Mutex blockieren
        if (needsFastPoll()) return
        if (!ioMutex.tryLock()) return
        try {
            if (needsFastPoll()) return
            client.drainBus(waitMs)
        } finally {
            ioMutex.unlock()
        }
    }

    private fun onBusTelegram(tel: de.dk8de.rotorapp.protocol.Telegram) {
        val cmd = tel.cmd.trim().uppercase()
        when {
            cmd == "SETPOSCC" -> applySetPosCc(tel)
            cmd == "SETPOSDG" -> applySetPosDgFromBus(tel)
            cmd.startsWith("ACK_SETPOSDG") -> applyAckSetPosDgFromBus(tel)
            cmd == "SETASELECT" || cmd.startsWith("ACK_GETASELECT") -> applyAntennaSelectFromBus(tel)
            cmd == "SETPWM" || cmd.startsWith("ACK_SETPWM") || cmd.startsWith("ACK_GETPWM") ->
                applyPwmFromBus(tel)
            cmd == "STOP" -> applyStopFromBus(tel)
            cmd == "ERR" || cmd.startsWith("ACK_GETERR") || cmd.startsWith("ACK_ERR") ->
                applyErrFromBus(tel)
            cmd == "WARN" || cmd.startsWith("ACK_GETWARN") -> applyWarnFromBus(tel)
            cmd.startsWith("ACK_GETPOSDG") || cmd.startsWith("ACK_POSDG") -> applyPosAckFromBus(tel)
        }
    }

    /**
     * Fremdes SETPWM / ACK_SETPWM / ACK_GETPWM → Slider anhand Slave-ID (Profil AZ/EL).
     * Eigenes Echo (src==master bzw. ACK an uns) ignorieren — State schon gesetzt.
     */
    private fun applyPwmFromBus(tel: de.dk8de.rotorapp.protocol.Telegram) {
        val cmd = tel.cmd.trim().uppercase()
        val saz = profile.slaveAz
        val sel = profile.slaveEl
        val isSet = cmd == "SETPWM"
        val isAck = cmd.startsWith("ACK_SETPWM") || cmd.startsWith("ACK_GETPWM")
        if (!isSet && !isAck) return

        val axis = when {
            isSet && tel.dst == saz -> RotorAxis.AZ
            isSet && profile.enableEl && tel.dst == sel -> RotorAxis.EL
            isAck && tel.src == saz -> RotorAxis.AZ
            isAck && profile.enableEl && tel.src == sel -> RotorAxis.EL
            else -> return
        }

        // Eigenes SETPWM-Echo / eigenes ACK — UI schon aktualisiert
        if (isSet && tel.src == profile.masterId) return
        if (isAck && tel.dst == profile.masterId) return

        val pct = de.dk8de.rotorapp.protocol.Rs485Protocol.parseDegree(tel.params)
            ?.toInt()
            ?.coerceIn(0, 100)
            ?: return

        val prev = _state.value
        val cur = when (axis) {
            RotorAxis.AZ -> prev.pwmAz
            RotorAxis.EL -> prev.pwmEl
        }
        if (cur == pct) return

        _state.value = when (axis) {
            RotorAxis.AZ -> prev.copy(
                pwmAz = pct,
                lastLog = "Bus SETPWM AZ $pct%",
            )
            RotorAxis.EL -> prev.copy(
                pwmEl = pct,
                lastLog = "Bus SETPWM EL $pct%",
            )
        }
    }

    /** SETASELECT Broadcast (DST 255) oder ACK_GETASELECT — UI an HW-Auswahl. */
    private fun applyAntennaSelectFromBus(tel: de.dk8de.rotorapp.protocol.Telegram) {
        val cmd = tel.cmd.trim().uppercase()
        val isAck = cmd.startsWith("ACK_GETASELECT")
        if (!isAck) {
            val dstOk = tel.dst == de.dk8de.rotorapp.protocol.Rs485Protocol.BROADCAST_DST
            if (!dstOk) return
        }
        val n = de.dk8de.rotorapp.protocol.Rs485Protocol.parseDegree(tel.params)?.toInt() ?: return
        if (n !in 1..3) return
        val prev = _state.value
        if (prev.selectedAntenna == n) return
        azDipoleLastRotorAz = null
        _state.value = prev.copy(
            selectedAntenna = n,
            azDipoleDisplayBearing = null,
            lastLog = if (isAck) "GETASELECT $n" else "Bus SETASELECT $n",
        )
    }

    /**
     * SETPOSCC = Fernbedienung-Vorschau: nur Sollzeiger, kein SETPOSDG, nicht passiv.
     * Die Fernbedienung sendet SETPOSDG selbst; die App pollt weiter aktiv.
     */
    private fun applySetPosCc(tel: de.dk8de.rotorapp.protocol.Telegram) {
        val (angle, rid) = de.dk8de.rotorapp.protocol.Rs485Protocol.parseSetPosCc(tel.params)
        if (angle == null) return
        val saz = profile.slaveAz
        val sel = profile.slaveEl

        // DST egal — Achse nur aus ;rotor_id (wie #2:7:SETPOSCC:154,60;20:…)
        val axis = when {
            rid == saz -> RotorAxis.AZ
            rid == sel && profile.enableEl -> RotorAxis.EL
            rid != null -> return // fremde Rotor-ID
            !profile.enableEl -> RotorAxis.AZ // Altformat ohne ID
            else -> return
        }

        val now = SystemClock.elapsedRealtime()
        val deg = if (axis == RotorAxis.AZ) wrapAz(angle) else clampEl(angle)
        when (axis) {
            RotorAxis.AZ -> {
                if (!_state.value.moving && now < azIgnoreCcUntilMs) return
                _state.value = takeBusControl(_state.value).copy(
                    azCompassTarget = deg,
                    azDipoleDisplayBearing = null,
                    lastLog = "SETPOSCC AZ ${"%.1f".format(deg)}°",
                )
            }
            RotorAxis.EL -> {
                if (!profile.enableEl) return
                if (!_state.value.moving && now < elIgnoreCcUntilMs) return
                _state.value = takeBusControl(_state.value).copy(
                    elCompassTarget = deg,
                    lastLog = "SETPOSCC EL ${"%.1f".format(deg)}°",
                )
            }
        }
    }

    private fun applySetPosDgFromBus(tel: de.dk8de.rotorapp.protocol.Telegram) {
        val saz = profile.slaveAz
        val sel = profile.slaveEl
        if (tel.dst != saz && !(profile.enableEl && tel.dst == sel)) return
        // Eigenes Echo: src == master — Zustand haben wir schon gesetzt
        if (tel.src == profile.masterId) return

        val degRaw = de.dk8de.rotorapp.protocol.Rs485Protocol.parseDegree(tel.params) ?: return
        val axis = if (tel.dst == saz) RotorAxis.AZ else RotorAxis.EL
        applyForeignSetPosDg(axis, degRaw, source = "SETPOSDG")
    }

    /**
     * Fremdes ACK_SETPOSDG (Antwort an anderen Master, z. B. Bridge `#20:0:ACK_SETPOSDG:…`).
     * Die App sieht oft nur das ACK auf dem Bus, nicht den TX-SETPOSDG der Bridge.
     */
    private fun applyAckSetPosDgFromBus(tel: de.dk8de.rotorapp.protocol.Telegram) {
        val saz = profile.slaveAz
        val sel = profile.slaveEl
        if (tel.src != saz && !(profile.enableEl && tel.src == sel)) return
        // Eigenes ACK an unseren Master — setAzimuth hat den Zustand schon gesetzt
        if (tel.dst == profile.masterId) return

        val degRaw = de.dk8de.rotorapp.protocol.Rs485Protocol.parseDegree(tel.params) ?: return
        val axis = if (tel.src == saz) RotorAxis.AZ else RotorAxis.EL
        applyForeignSetPosDg(axis, degRaw, source = "ACK_SETPOSDG")
    }

    /**
     * Fernbedienung / anderer Master: SETPOSDG → Soll übernehmen und aktiv weiter pollen
     * (kein busPassive — App bleibt Master für GETPOS).
     */
    private fun applyForeignSetPosDg(axis: RotorAxis, degRaw: Double, source: String) {
        val deg = if (axis == RotorAxis.AZ) wrapAz(degRaw) else clampEl(degRaw)
        val now = SystemClock.elapsedRealtime()
        armSetPosPollGrace()
        when (axis) {
            RotorAxis.AZ -> {
                azIgnoreCcUntilMs = now + SETPOSCC_SUPPRESS_MS
                azDipoleLastRotorAz = null
                val prev = takeBusControl(_state.value)
                val atTarget = prev.azDeg != null && abs(prev.azDeg - deg) <= 0.8
                _state.value = prev.copy(
                    azOnline = true,
                    azTarget = deg,
                    azCompassTarget = null,
                    azDipoleDisplayBearing = null,
                    moving = !atTarget,
                    lastLog = "Bus $source AZ ${"%.1f".format(deg)}°",
                )
            }
            RotorAxis.EL -> {
                if (!profile.enableEl) return
                elIgnoreCcUntilMs = now + SETPOSCC_SUPPRESS_MS
                val prev = takeBusControl(_state.value)
                val atTarget = prev.elDeg != null && abs(prev.elDeg - deg) <= 0.8
                _state.value = prev.copy(
                    elOnline = true,
                    elTarget = deg,
                    elCompassTarget = null,
                    moving = !atTarget,
                    lastLog = "Bus $source EL ${"%.1f".format(deg)}°",
                )
            }
        }
    }

    /** Positions-ACK an beliebigen Master — nur im Passiv-Modus (fremdes Polling). */
    private fun applyPosAckFromBus(tel: de.dk8de.rotorapp.protocol.Telegram) {
        // Aktives eigenes GETPOSDG aktualisiert den Smoother in pollPositions.
        // Hier mitlaufen würde EL doppelt (Bus-ACK + poll) mit ~0 ms Abstand → Ruckler.
        if (!_state.value.busPassive) return
        val deg = de.dk8de.rotorapp.protocol.Rs485Protocol.parseDegree(tel.params) ?: return
        val now = SystemClock.elapsedRealtime()
        val cur = _state.value
        when (tel.src) {
            profile.slaveAz -> {
                azFailStreak = 0
                lastOkPollMs = System.currentTimeMillis()
                azSmoother.setDynamic(cur.moving || cur.busPassive)
                azSmoother.updateSample(
                    deg.toFloat(),
                    nowMs = now,
                    expectedPeriodS = if (cur.busPassive) 0.25f else 0.5f,
                )
                val near = cur.azTarget != null && abs(deg - cur.azTarget!!) <= 0.8
                val elStillMoving = profile.enableEl && cur.elTarget != null &&
                    cur.elDeg != null && abs(cur.elDeg - cur.elTarget!!) > 0.8
                _state.value = cur.copy(
                    azOnline = true,
                    azDeg = deg,
                    azSmoothDeg = azSmoother.current()?.toDouble(),
                    moving = if (cur.busPassive) {
                        (!near) || elStillMoving
                    } else {
                        cur.moving
                    },
                )
            }
            profile.slaveEl -> {
                if (!profile.enableEl) return
                elFailStreak = 0
                lastOkPollMs = System.currentTimeMillis()
                elSmoother.setDynamic(cur.moving || cur.busPassive)
                elSmoother.updateSample(
                    deg.toFloat(),
                    nowMs = now,
                    expectedPeriodS = if (cur.busPassive) 0.25f else 0.5f,
                )
                val near = cur.elTarget != null && abs(deg - cur.elTarget!!) <= 0.8
                val azStillMoving = cur.azTarget != null &&
                    cur.azDeg != null && abs(cur.azDeg - cur.azTarget!!) > 0.8
                _state.value = cur.copy(
                    elOnline = true,
                    elDeg = deg,
                    elSmoothDeg = elSmoother.current()?.toDouble(),
                    moving = if (cur.busPassive) {
                        (!near) || azStillMoving
                    } else {
                        cur.moving
                    },
                )
            }
        }
    }

    private fun applyStopFromBus(tel: de.dk8de.rotorapp.protocol.Telegram) {
        val saz = profile.slaveAz
        val sel = profile.slaveEl
        if (tel.dst != saz && !(profile.enableEl && tel.dst == sel)) return
        if (tel.src == profile.masterId) return
        val prev = _state.value
        _state.value = when {
            tel.dst == saz -> prev.copy(
                moving = profile.enableEl && prev.elTarget != null && prev.moving,
                lastLog = "Bus STOP AZ",
            )
            else -> prev.copy(
                moving = prev.azTarget != null && prev.moving,
                lastLog = "Bus STOP EL",
            )
        }
    }

    /**
     * Broadcast `#SRC:255:ERR:code:CS$` oder ACK_GETERR — Motor gestoppt, Fehler gelatched.
     */
    private fun applyErrFromBus(tel: de.dk8de.rotorapp.protocol.Telegram) {
        val saz = profile.slaveAz
        val sel = profile.slaveEl
        val src = tel.src
        val axis = when {
            src == saz -> RotorAxis.AZ
            profile.enableEl && src == sel -> RotorAxis.EL
            else -> return
        }
        // ERR an 255 oder an unseren Master; fremde DST ignorieren (außer Broadcast)
        val dstOk = tel.dst == de.dk8de.rotorapp.protocol.Rs485Protocol.BROADCAST_DST ||
            tel.dst == profile.masterId ||
            tel.cmd.uppercase().startsWith("ACK_")
        if (!dstOk) return

        val code = de.dk8de.rotorapp.protocol.Rs485Protocol.parseDegree(tel.params)?.toInt() ?: return
        val prev = _state.value
        val label = if (axis == RotorAxis.AZ) "AZ" else "EL"
        val msg = if (code == 0) {
            ""
        } else {
            RotorCodes.formatError(label, code)
        }
        _state.value = when (axis) {
            RotorAxis.AZ -> prev.copy(
                azErrorCode = code,
                azOnline = true,
                moving = profile.enableEl && prev.elTarget != null && prev.elErrorCode == 0 && prev.moving,
                azTarget = if (code != 0) null else prev.azTarget,
                azCompassTarget = if (code != 0) null else prev.azCompassTarget,
                statusText = faultStatusText(
                    prev.copy(azErrorCode = code),
                ).ifBlank { msg },
                lastLog = if (code == 0) "ERR AZ gelöscht" else "Bus ERR AZ $code",
            )
            RotorAxis.EL -> prev.copy(
                elErrorCode = code,
                elOnline = true,
                moving = prev.azTarget != null && prev.azErrorCode == 0 && prev.moving,
                elTarget = if (code != 0) null else prev.elTarget,
                elCompassTarget = if (code != 0) null else prev.elCompassTarget,
                statusText = faultStatusText(
                    prev.copy(elErrorCode = code),
                ).ifBlank { msg },
                lastLog = if (code == 0) "ERR EL gelöscht" else "Bus ERR EL $code",
            )
        }
    }

    /** WARN-Broadcast oder ACK_GETWARN vom Slave. */
    private fun applyWarnFromBus(tel: de.dk8de.rotorapp.protocol.Telegram) {
        val saz = profile.slaveAz
        val sel = profile.slaveEl
        val src = tel.src
        val axis = when {
            src == saz -> RotorAxis.AZ
            profile.enableEl && src == sel -> RotorAxis.EL
            else -> return
        }
        val ids = RotorCodes.parseWarnIds(tel.params)
        val prev = _state.value
        val label = if (axis == RotorAxis.AZ) "AZ" else "EL"
        val next = when (axis) {
            RotorAxis.AZ -> prev.copy(azWarnIds = ids, azOnline = true)
            RotorAxis.EL -> prev.copy(elWarnIds = ids, elOnline = true)
        }
        _state.value = next.copy(
            statusText = faultStatusText(next),
            lastLog = RotorCodes.formatWarnings(label, ids).ifBlank { "GETWARN $label: 0" },
        )
    }

    /** Statuszeile: Fehler vor Warnung vor Homing/Referenz. */
    private fun faultStatusText(s: RotorLiveState): String {
        val errs = buildList {
            if (s.azErrorCode != 0) add(RotorCodes.formatError("AZ", s.azErrorCode))
            if (profile.enableEl && s.elErrorCode != 0) {
                add(RotorCodes.formatError("EL", s.elErrorCode))
            }
        }
        if (errs.isNotEmpty()) return errs.joinToString(" · ")
        val warns = buildList {
            val azW = RotorCodes.formatWarnings("AZ", s.azWarnIds)
            if (azW.isNotEmpty()) add(azW)
            if (profile.enableEl) {
                val elW = RotorCodes.formatWarnings("EL", s.elWarnIds)
                if (elW.isNotEmpty()) add(elW)
            }
        }
        if (warns.isNotEmpty()) return warns.joinToString(" · ")
        return ""
    }

    /** Eigene Bedienung: aktiven Master-Modus zurücknehmen. */
    private fun takeBusControl(prev: RotorLiveState): RotorLiveState =
        prev.copy(busPassive = false)

    suspend fun pollPositions(expectedPeriodS: Float = 0.25f) {
        if (!client.isConnected) return
        if (_state.value.busPassive) return
        if (userCmdPending) return
        // Immer warten: Idle-GETPOS 1×/s darf nicht an Temp/Wind/ACC/Sniff scheitern
        // (zweiter Master braucht zuverlässigen Takt).
        ioMutex.withLock {
            if (userCmdPending) return@withLock
            if (_state.value.busPassive) return@withLock
            pollPositionsLocked(expectedPeriodS)
        }
    }

    private suspend fun pollPositionsLocked(expectedPeriodS: Float) {
            if (_state.value.busPassive) return
            if (_state.value.homing) {
                pollReferenceLocked()
                return
            }
            val before = _state.value
            val nowWall = SystemClock.elapsedRealtime()

            // Offline nie pollen (nur probeOfflineAxes / 5 s GETREF im Idle).
            // Fährt nur eine Achse → nur die pollen; Gegenachse online: selten Keepalive.
            val azBusy = before.azOnline && before.azTarget != null
            val elBusy = profile.enableEl && before.elOnline && before.elTarget != null

            val pollAzPrimary = before.azOnline && (azBusy || !elBusy)
            val pollElPrimary = profile.enableEl && before.elOnline && (elBusy || !azBusy)
            val pollAzKeepalive = before.azOnline && elBusy && !azBusy &&
                nowWall - lastAzKeepaliveMs >= SIBLING_KEEPALIVE_MS
            val pollElKeepalive = profile.enableEl && before.elOnline && azBusy && !elBusy &&
                nowWall - lastElKeepaliveMs >= SIBLING_KEEPALIVE_MS

            var az: Double? = null
            var azSampleMs = 0L
            var el: Double? = null
            var elSampleMs = 0L

            // Fahrende Achse zuerst (Deadman) — kurze Timeouts für zügigen Takt (~Bridge 200 ms)
            val posTo = if (needsFastPoll()) POS_TIMEOUT_FAST_MS else POS_TIMEOUT_ONLINE_MS
            if (elBusy && !azBusy) {
                if (pollElPrimary) {
                    el = runCatching {
                        client.getPosDg(profile.slaveEl, timeoutMs = posTo)
                    }.getOrNull()
                    if (el != null) elSampleMs = SystemClock.elapsedRealtime()
                }
                if (userCmdPending || client.isAwaitAborted) return
                if (pollAzKeepalive) {
                    lastAzKeepaliveMs = SystemClock.elapsedRealtime()
                    az = runCatching {
                        client.getPosDg(profile.slaveAz, timeoutMs = SIBLING_KEEPALIVE_TIMEOUT_MS)
                    }.getOrNull()
                    if (az != null) azSampleMs = SystemClock.elapsedRealtime()
                }
            } else if (azBusy && !elBusy) {
                if (pollAzPrimary) {
                    az = runCatching {
                        client.getPosDg(profile.slaveAz, timeoutMs = posTo)
                    }.getOrNull()
                    if (az != null) azSampleMs = SystemClock.elapsedRealtime()
                }
                if (userCmdPending || client.isAwaitAborted) return
                if (pollElKeepalive) {
                    lastElKeepaliveMs = SystemClock.elapsedRealtime()
                    el = runCatching {
                        client.getPosDg(profile.slaveEl, timeoutMs = SIBLING_KEEPALIVE_TIMEOUT_MS)
                    }.getOrNull()
                    if (el != null) elSampleMs = SystemClock.elapsedRealtime()
                }
            } else {
                if (pollAzPrimary) {
                    az = runCatching {
                        client.getPosDg(profile.slaveAz, timeoutMs = posTo)
                    }.getOrNull()
                    if (az != null) azSampleMs = SystemClock.elapsedRealtime()
                }
                if (userCmdPending || client.isAwaitAborted) return
                if (pollElPrimary) {
                    el = runCatching {
                        client.getPosDg(profile.slaveEl, timeoutMs = posTo)
                    }.getOrNull()
                    if (el != null) elSampleMs = SystemClock.elapsedRealtime()
                }
            }
            if (userCmdPending || client.isAwaitAborted) return

            val cur = _state.value
            var azOnline = cur.azOnline
            var elOnline = if (profile.enableEl) cur.elOnline else false
            var azDeg = cur.azDeg
            var elDeg = cur.elDeg
            var azReferenced = cur.azReferenced
            var elReferenced = cur.elReferenced
            var azSmooth = cur.azSmoothDeg
            var elSmooth = cur.elSmoothDeg
            var azTarget = cur.azTarget
            var elTarget = cur.elTarget
            var azCompass = cur.azCompassTarget
            var elCompass = cur.elCompassTarget

            // Nur auswerten, wenn wir diese Achse in diesem Zyklus wirklich abgefragt haben
            val azQueried = pollAzPrimary || pollAzKeepalive
            val elQueried = pollElPrimary || pollElKeepalive

            if (azQueried) {
                if (az != null) {
                    azFailStreak = 0
                    azOnline = true
                    azDeg = az
                    lastOkPollMs = System.currentTimeMillis()
                } else {
                    // Keepalive-Timeout: ein Fehlversuch reicht → offline (nicht den Bus blockieren)
                    val streakLimit = if (pollAzKeepalive && !pollAzPrimary) 1 else FAIL_STREAK_OFFLINE
                    azFailStreak++
                    if (azFailStreak >= streakLimit) {
                        azOnline = false
                        azDeg = null
                        azSmooth = null
                        azReferenced = false
                        azTarget = null
                        azCompass = null
                        azSmoother.clear()
                        nextAzProbeMs = SystemClock.elapsedRealtime() + OFFLINE_RETRY_MS
                    }
                }
            }

            if (profile.enableEl && elQueried) {
                if (el != null) {
                    elFailStreak = 0
                    elOnline = true
                    elDeg = el
                    lastOkPollMs = System.currentTimeMillis()
                } else {
                    val streakLimit = if (pollElKeepalive && !pollElPrimary) 1 else FAIL_STREAK_OFFLINE
                    elFailStreak++
                    if (elFailStreak >= streakLimit) {
                        elOnline = false
                        elDeg = null
                        elSmooth = null
                        elReferenced = false
                        elTarget = null
                        elCompass = null
                        elSmoother.clear()
                        nextElProbeMs = SystemClock.elapsedRealtime() + OFFLINE_RETRY_MS
                    }
                }
            } else if (!profile.enableEl) {
                elOnline = false
                elDeg = null
                elSmooth = null
            }

            val motorAz = azTarget
            val motorEl = elTarget
            val azMoving = azOnline && motorAz != null && when {
                az != null -> abs(az - motorAz) > 0.8
                azQueried -> true // Timeout während Abfrage: noch unterwegs annehmen
                else -> azDeg == null || abs(azDeg - motorAz) > 0.8
            }
            val elMoving = profile.enableEl && elOnline && motorEl != null && when {
                el != null -> abs(el - motorEl) > 0.8
                elQueried -> true
                else -> elDeg == null || abs(elDeg - motorEl) > 0.8
            }
            val axisMoving = azMoving || elMoving
            val moving = cur.moving && axisMoving

            azSmoother.setDynamic(axisMoving || cur.homing)
            elSmoother.setDynamic(axisMoving || cur.homing)
            if (az != null) {
                azSmoother.updateSample(
                    az.toFloat(),
                    nowMs = azSampleMs,
                    expectedPeriodS = expectedPeriodS,
                )
                azSmooth = azSmoother.current()?.toDouble()
            }
            if (profile.enableEl && el != null) {
                elSmoother.updateSample(
                    el.toFloat(),
                    nowMs = elSampleMs,
                    expectedPeriodS = expectedPeriodS,
                )
                elSmooth = elSmoother.current()?.toDouble()
            }

            _state.value = cur.copy(
                connected = true,
                azDeg = azDeg,
                elDeg = if (profile.enableEl) elDeg else null,
                azSmoothDeg = azSmooth,
                elSmoothDeg = if (profile.enableEl) elSmooth else null,
                azOnline = azOnline,
                elOnline = if (profile.enableEl) elOnline else false,
                azReferenced = azReferenced,
                elReferenced = if (profile.enableEl) elReferenced else true,
                azTarget = azTarget,
                elTarget = elTarget,
                azCompassTarget = azCompass,
                elCompassTarget = elCompass,
                moving = moving,
                statusText = statusTextFor(azOnline, elOnline, azReferenced, elReferenced, cur),
            )
    }

    /**
     * Offline-Achsen alle 5 s mit kurzem GETREF anpingen — sobald Antwort kommt → online.
     */
    suspend fun probeOfflineAxes() {
        if (!client.isConnected) return
        if (_state.value.busPassive) return
        if (userCmdPending) return
        if (!ioMutex.tryLock()) return
        try {
            if (userCmdPending) return
            probeOfflineAxesLocked()
        } finally {
            ioMutex.unlock()
        }
    }

    private suspend fun probeOfflineAxesLocked() {
        val before = _state.value
        // Während Fahrt / kurz nach SETPOS / Homing: Offline NICHT anfassen (Deadman der Online-Achse)
        if (before.homing || before.moving || before.busPassive) return
        if (isMotionHoldActive() || idleExtrasDeferred()) return
        val nowWall = SystemClock.elapsedRealtime()
        var cur = _state.value

        if (!cur.azOnline && nowWall >= nextAzProbeMs) {
            val azRef = runCatching {
                client.getRef(profile.slaveAz, timeoutMs = POS_TIMEOUT_OFFLINE_MS)
            }.getOrNull()
            nextAzProbeMs = SystemClock.elapsedRealtime() + OFFLINE_RETRY_MS
            if (azRef != null) {
                azFailStreak = 0
                lastOkPollMs = System.currentTimeMillis()
                cur = cur.copy(
                    azOnline = true,
                    azReferenced = azRef == 1,
                    statusText = statusTextFor(
                        true,
                        cur.elOnline,
                        azRef == 1,
                        cur.elReferenced,
                        cur,
                    ),
                    lastLog = "GETREF AZ=$azRef (online)",
                )
                _state.value = cur
                // Position sofort nachziehen
                val az = runCatching {
                    client.getPosDg(profile.slaveAz, timeoutMs = POS_TIMEOUT_ONLINE_MS)
                }.getOrNull()
                if (az != null) {
                    azSmoother.setDynamic(true)
                    azSmoother.updateSample(
                        az.toFloat(),
                        nowMs = SystemClock.elapsedRealtime(),
                        expectedPeriodS = 0.5f,
                    )
                    cur = _state.value.copy(
                        azDeg = az,
                        azSmoothDeg = azSmoother.current()?.toDouble(),
                    )
                    _state.value = cur
                }
            }
        }

        if (profile.enableEl && !cur.elOnline && nowWall >= nextElProbeMs) {
            val elRef = runCatching {
                client.getRef(profile.slaveEl, timeoutMs = POS_TIMEOUT_OFFLINE_MS)
            }.getOrNull()
            nextElProbeMs = SystemClock.elapsedRealtime() + OFFLINE_RETRY_MS
            if (elRef != null) {
                elFailStreak = 0
                lastOkPollMs = System.currentTimeMillis()
                val elType = runCatching {
                    client.getRotorType(profile.slaveEl, timeoutMs = POS_TIMEOUT_OFFLINE_MS)
                }.getOrNull()
                val resolvedType = elType ?: profile.cachedElRotorType()
                rememberElRotorTypeIfChanged(elType)
                cur = _state.value.copy(
                    elOnline = true,
                    elReferenced = elRef == 1,
                    elRotorType = resolvedType,
                    elMaxDeg = elMaxFromType(resolvedType),
                    statusText = statusTextFor(
                        _state.value.azOnline,
                        true,
                        _state.value.azReferenced,
                        elRef == 1,
                        _state.value,
                    ),
                    lastLog = "GETREF EL=$elRef TYPE=$resolvedType (online)",
                )
                _state.value = cur
                val el = runCatching {
                    client.getPosDg(profile.slaveEl, timeoutMs = POS_TIMEOUT_ONLINE_MS)
                }.getOrNull()
                if (el != null) {
                    elSmoother.setDynamic(true)
                    elSmoother.updateSample(
                        el.toFloat(),
                        nowMs = SystemClock.elapsedRealtime(),
                        expectedPeriodS = 0.5f,
                    )
                    _state.value = _state.value.copy(
                        elDeg = el,
                        elSmoothDeg = elSmoother.current()?.toDouble(),
                    )
                }
            }
        }
    }

    suspend fun pollReference() {
        if (!client.isConnected) return
        if (_state.value.busPassive) return
        // Homing: GETREF muss zuverlässig und zyklisch laufen (Deadman), nicht per tryLock ausfallen
        if (_state.value.homing) {
            ioMutex.withLock {
                if (_state.value.busPassive) return@withLock
                client.clearAbortAwait()
                pollReferenceLocked()
            }
            return
        }
        // Nach SETPOS / während Fahrt: kein Idle-GETREF (Offline sowieso nie hier)
        if (userCmdPending || _state.value.moving || isMotionHoldActive() || idleExtrasDeferred()) return
        if (!ioMutex.tryLock()) return
        try {
            if (userCmdPending || _state.value.moving || isMotionHoldActive()) return
            pollReferenceLocked()
        } finally {
            ioMutex.unlock()
        }
    }

    suspend fun startHoming(axis: RotorAxis): Boolean = ioMutex.withLock {
        client.clearAbortAwait()
        ensureConnected()
        val dst = if (axis == RotorAxis.EL) profile.slaveEl else profile.slaveAz
        if (axis == RotorAxis.EL && !profile.enableEl) return@withLock false
        val ok = client.setRef(dst, 1)
        if (!ok) {
            _state.value = takeBusControl(_state.value).copy(
                lastLog = "SETREF fehlgeschlagen",
                statusText = "Homing fehlgeschlagen",
            )
            return@withLock false
        }
        _state.value = when (axis) {
            RotorAxis.AZ -> takeBusControl(_state.value).copy(
                azHoming = true,
                azOnline = true,
                azReferenced = false,
                azErrorCode = 0, // SETREF quittiert Fehler
                moving = false,
                azTarget = null,
                azCompassTarget = null,
                statusText = "Homing AZ…",
                lastLog = "SETREF:1 AZ",
            )
            RotorAxis.EL -> takeBusControl(_state.value).copy(
                elHoming = true,
                elOnline = true,
                elReferenced = false,
                elErrorCode = 0,
                moving = false,
                elTarget = null,
                elCompassTarget = null,
                statusText = "Homing EL…",
                lastLog = "SETREF:1 EL",
            )
        }
        true
    }

    suspend fun setAzimuth(displayDeg: Double): Boolean = ioMutex.withLock {
        client.clearAbortAwait()
        ensureConnected()
        val s = takeBusControl(_state.value)
        if (!s.azReferenced || s.azHoming) {
            _state.value = s.copy(
                lastLog = "AZ nicht referenziert – HOME nötig",
                statusText = "AZ nicht referenziert – HOME nötig",
            )
            return@withLock false
        }
        val display = AntennaMath.wrap360(displayDeg)
        val dipole = s.selectedDipole
        val offset = s.selectedOffsetDeg
        val currentRotor = s.azDeg
        val target = AntennaMath.rotorAzForDisplayBearing(
            displayBearingDeg = display,
            offsetDeg = offset,
            currentRotorAz = currentRotor,
            dipole = dipole,
            lastRotorAz = if (dipole) azDipoleLastRotorAz else null,
        )
        // UI sofort: Sollnadel + moving, bevor ACK kommt
        val lobe = if (dipole) {
            val primary = AntennaMath.rotorFromDisplay(display, offset)
            if (kotlin.math.abs(AntennaMath.wrap360(target - primary) - 180.0) < 1.0 ||
                kotlin.math.abs(target - AntennaMath.wrap360(primary + 180.0)) < 1.0
            ) {
                "Gegenkeule"
            } else {
                "Hauptkeule"
            }
        } else {
            null
        }
        if (dipole) {
            azDipoleLastRotorAz = target
        } else {
            azDipoleLastRotorAz = null
        }
        _state.value = s.copy(
            azTarget = target,
            azCompassTarget = null,
            azDipoleDisplayBearing = if (dipole) display else null,
            moving = true,
            lastLog = buildString {
                append("SETPOSDG AZ ${"%.1f".format(target)}°")
                append(" (Anzeige ${"%.1f".format(display)}°")
                if (lobe != null) append(", $lobe")
                append(")")
            },
            statusText = faultStatusText(s),
        )
        val ok = client.setPosDg(profile.slaveAz, target)
        if (ok) {
            azIgnoreCcUntilMs = SystemClock.elapsedRealtime() + SETPOSCC_SUPPRESS_MS
            armSetPosPollGrace()
            // Sofort GETPOS — kein Idle/GETREF-Offline dazwischen (Deadman)
            val az = runCatching {
                client.getPosDg(profile.slaveAz, timeoutMs = POS_TIMEOUT_ONLINE_MS)
            }.getOrNull()
            if (az != null) {
                azFailStreak = 0
                lastOkPollMs = System.currentTimeMillis()
                val nowMs = SystemClock.elapsedRealtime()
                azSmoother.setDynamic(true)
                azSmoother.updateSample(az.toFloat(), nowMs = nowMs, expectedPeriodS = 0.25f)
                _state.value = _state.value.copy(
                    azOnline = true,
                    azDeg = az,
                    azSmoothDeg = azSmoother.current()?.toDouble(),
                    moving = true,
                )
            }
        } else if (!client.isAwaitAborted) {
            _state.value = _state.value.copy(
                lastLog = "SETPOSDG AZ fehlgeschlagen",
                statusText = "SETPOSDG AZ fehlgeschlagen",
            )
        }
        ok
    }

    /**
     * Parken AZ: GETHOMEPOS lesen und per SETPOSDG anfahren (Rotorwinkel, kein Dipol-Offset).
     */
    suspend fun parkAzimuth(): Boolean = ioMutex.withLock {
        client.clearAbortAwait()
        ensureConnected()
        val s = takeBusControl(_state.value)
        if (!s.azReferenced || s.azHoming) {
            _state.value = s.copy(
                lastLog = "AZ nicht referenziert – HOME nötig",
                statusText = "AZ nicht referenziert – HOME nötig",
            )
            return@withLock false
        }
        val home = runCatching {
            client.getHomePos(profile.slaveAz, timeoutMs = 800)
        }.getOrNull()
        if (home == null) {
            _state.value = s.copy(
                lastLog = "GETHOMEPOS fehlgeschlagen",
                statusText = "GETHOMEPOS fehlgeschlagen",
            )
            return@withLock false
        }
        val target = AntennaMath.wrap360(home)
        azDipoleLastRotorAz = null
        _state.value = s.copy(
            azTarget = target,
            azCompassTarget = null,
            azDipoleDisplayBearing = null,
            moving = true,
            lastLog = "Parken AZ SETPOSDG ${"%.1f".format(target)}° (GETHOMEPOS)",
            statusText = faultStatusText(s),
        )
        val ok = client.setPosDg(profile.slaveAz, target)
        if (ok) {
            azIgnoreCcUntilMs = SystemClock.elapsedRealtime() + SETPOSCC_SUPPRESS_MS
            armSetPosPollGrace()
            val az = runCatching {
                client.getPosDg(profile.slaveAz, timeoutMs = POS_TIMEOUT_ONLINE_MS)
            }.getOrNull()
            if (az != null) {
                azFailStreak = 0
                lastOkPollMs = System.currentTimeMillis()
                val nowMs = SystemClock.elapsedRealtime()
                azSmoother.setDynamic(true)
                azSmoother.updateSample(az.toFloat(), nowMs = nowMs, expectedPeriodS = 0.25f)
                _state.value = _state.value.copy(
                    azOnline = true,
                    azDeg = az,
                    azSmoothDeg = azSmoother.current()?.toDouble(),
                    moving = true,
                )
            }
        } else if (!client.isAwaitAborted) {
            _state.value = _state.value.copy(
                lastLog = "Parken AZ fehlgeschlagen",
                statusText = "Parken AZ fehlgeschlagen",
            )
        }
        ok
    }

    suspend fun setElevation(deg: Double): Boolean = ioMutex.withLock {
        client.clearAbortAwait()
        if (!profile.enableEl) return@withLock false
        ensureConnected()
        val s = takeBusControl(_state.value)
        if (!s.elReferenced || s.elHoming) {
            _state.value = s.copy(
                lastLog = "EL nicht referenziert – HOME nötig",
                statusText = "EL nicht referenziert – HOME nötig",
            )
            return@withLock false
        }
        val clamped = clampEl(deg)
        // UI sofort aktualisieren — Polling blockiert den Touch nicht mehr
        _state.value = s.copy(
            elTarget = clamped,
            elCompassTarget = null,
            moving = true,
            lastLog = "SETPOSDG EL ${"%.1f".format(clamped)}°",
            statusText = faultStatusText(s),
        )
        val ok = client.setPosDg(profile.slaveEl, clamped)
        if (ok) {
            elIgnoreCcUntilMs = SystemClock.elapsedRealtime() + SETPOSCC_SUPPRESS_MS
            armSetPosPollGrace()
            // Sofort GETPOS auf der Online-Achse — Offline-GETREF darf nicht dazwischen
            val el = runCatching {
                client.getPosDg(profile.slaveEl, timeoutMs = POS_TIMEOUT_ONLINE_MS)
            }.getOrNull()
            if (el != null) {
                elFailStreak = 0
                lastOkPollMs = System.currentTimeMillis()
                val nowMs = SystemClock.elapsedRealtime()
                elSmoother.setDynamic(true)
                elSmoother.updateSample(el.toFloat(), nowMs = nowMs, expectedPeriodS = 0.25f)
                _state.value = _state.value.copy(
                    elOnline = true,
                    elDeg = el,
                    elSmoothDeg = elSmoother.current()?.toDouble(),
                    moving = true,
                )
            }
        } else if (!client.isAwaitAborted) {
            _state.value = _state.value.copy(
                lastLog = "SETPOSDG EL fehlgeschlagen",
                statusText = "SETPOSDG EL fehlgeschlagen",
            )
        }
        ok
    }

    suspend fun stopAxis(axis: RotorAxis): Boolean = ioMutex.withLock {
        client.clearAbortAwait()
        ensureConnected()
        if (axis == RotorAxis.EL && !profile.enableEl) return@withLock false
        val dst = if (axis == RotorAxis.EL) profile.slaveEl else profile.slaveAz
        val ok = client.stop(dst)
        val prev = takeBusControl(_state.value)
        _state.value = when (axis) {
            RotorAxis.AZ -> prev.copy(
                lastLog = if (ok) "STOP AZ OK" else "STOP AZ fehlgeschlagen",
                statusText = if (ok) faultStatusText(prev) else "STOP AZ fehlgeschlagen",
                moving = profile.enableEl && prev.elTarget != null && prev.moving,
            )
            RotorAxis.EL -> prev.copy(
                lastLog = if (ok) "STOP EL OK" else "STOP EL fehlgeschlagen",
                statusText = if (ok) faultStatusText(prev) else "STOP EL fehlgeschlagen",
                moving = prev.azTarget != null && prev.moving,
            )
        }
        ok
    }

    suspend fun setPwm(axis: RotorAxis, percent: Int): Boolean = ioMutex.withLock {
        ensureConnected()
        if (axis == RotorAxis.EL && !profile.enableEl) return@withLock false
        val dst = if (axis == RotorAxis.EL) profile.slaveEl else profile.slaveAz
        val p = percent.coerceIn(30, 100)
        val ok = client.setPwm(dst, p)
        if (ok) {
            _state.value = when (axis) {
                RotorAxis.AZ -> _state.value.copy(pwmAz = p, lastLog = "SETPWM AZ $p%")
                RotorAxis.EL -> _state.value.copy(pwmEl = p, lastLog = "SETPWM EL $p%")
            }
        } else {
        _state.value = _state.value.copy(
                lastLog = "SETPWM fehlgeschlagen",
                statusText = "SETPWM fehlgeschlagen",
            )
        }
        ok
    }

    suspend fun refreshPwm() = ioMutex.withLock {
        if (!client.isConnected) return@withLock
        refreshPwmLocked()
    }

    suspend fun refreshTemps(): Boolean {
        if (!client.isConnected) return false
        if (userCmdPending || idleExtrasDeferred()) return false
        // Warten statt tryLock: Idle-Takt 2 s darf nicht an GETPOS scheitern
        return ioMutex.withLock {
            if (userCmdPending) return@withLock false
            refreshTempsLocked()
            true
        }
    }

    suspend fun refreshAntennas() = ioMutex.withLock {
        if (!client.isConnected) return@withLock
        refreshAntennasLocked()
    }

    /** Antenne 1–3 wählen und SETASELECT broadcasten (kein Nachdrehen). */
    suspend fun selectAntenna(slot: Int): Boolean = ioMutex.withLock {
        if (!client.isConnected) return@withLock false
        val n = slot.coerceIn(1, 3)
        val prev = _state.value
        if (prev.selectedAntenna != n) {
            azDipoleLastRotorAz = null
            _state.value = prev.copy(
                selectedAntenna = n,
                azDipoleDisplayBearing = null,
                lastLog = "Antenne $n gewählt",
            )
        }
        runCatching { client.broadcastAntennaSelect(n) }
        true
    }

    /** Antennen-Slot am AZ-Rotor schreiben und State aktualisieren. */
    suspend fun writeAntennaSlot(slot: Int, data: AntennaSlot): Boolean = ioMutex.withLock {
        ensureConnected()
        val n = slot.coerceIn(1, 3)
        val dst = profile.slaveAz
        val off = AntennaMath.wrap360(data.offsetDeg.coerceIn(0.0, 360.0))
        val opening = data.openingDeg.coerceIn(0.0, 360.0)
        val range = data.rangeKm.coerceIn(0, 99_999)
        val name = data.name.trim().take(9)

        val okOff = runCatching { client.setAntOff(dst, n, off) }.getOrDefault(false)
        val okAng = runCatching { client.setAntAngle(dst, n, opening) }.getOrDefault(false)
        val okDis = runCatching { client.setAntDis(dst, n, range) }.getOrDefault(false)
        val okDp = runCatching { client.setAntDp(dst, n, data.dipole) }.getOrDefault(false)
        val okName = if (name.isNotEmpty()) {
            runCatching { client.setAntName(dst, n, name) }.getOrDefault(false)
        } else {
            true
        }
        val ok = okOff && okAng && okDis && okDp && okName

        val readName = runCatching { client.getAntName(dst, n) }.getOrNull()
            ?: name.ifEmpty { _state.value.antennas.getOrNull(n - 1)?.name.orEmpty() }
        val updated = AntennaSlot(
            offsetDeg = off,
            openingDeg = opening,
            rangeKm = range,
            dipole = data.dipole,
            name = readName,
        )
        val list = _state.value.antennas.toMutableList()
        while (list.size < 3) list.add(AntennaSlot())
        list[n - 1] = updated
        _state.value = _state.value.copy(
            antennas = list.take(3),
            lastLog = if (ok) "Antenne $n gespeichert" else "Antenne $n Speichern teilweise fehlgeschlagen",
            statusText = if (ok) faultStatusText(_state.value) else "Antenne speichern fehlgeschlagen",
        )
        ok
    }

    private suspend fun refreshAntennasLocked() {
        val dst = profile.slaveAz
        val to = 450L
        val slots = (1..3).map { n ->
            val off = runCatching { client.getAntOff(dst, n, timeoutMs = to) }.getOrNull()
            val ang = runCatching { client.getAntAngle(dst, n, timeoutMs = to) }.getOrNull()
            val dis = runCatching { client.getAntDis(dst, n, timeoutMs = to) }.getOrNull()
            val dp = runCatching { client.getAntDp(dst, n, timeoutMs = to) }.getOrNull()
            val name = runCatching { client.getAntName(dst, n, timeoutMs = to) }.getOrNull()
            val prev = _state.value.antennas.getOrNull(n - 1) ?: AntennaSlot()
            AntennaSlot(
                offsetDeg = off ?: prev.offsetDeg,
                openingDeg = ang ?: prev.openingDeg,
                rangeKm = dis ?: prev.rangeKm,
                dipole = dp ?: prev.dipole,
                name = name ?: prev.name,
            )
        }
        var selected = _state.value.selectedAntenna
        val ctrl = profile.controllerId
        if (ctrl in 1..254) {
            val hw = runCatching { client.getAntennaSelect(ctrl, timeoutMs = to) }.getOrNull()
            if (hw != null) selected = hw
        }
        _state.value = _state.value.copy(
            antennas = slots,
            selectedAntenna = selected.coerceIn(1, 3),
            lastLog = "Antennen gelesen · Slot $selected",
        )
    }

    private suspend fun refreshPwmLocked() {
        val s = _state.value
        val az = if (s.azOnline) {
            runCatching { client.getPwm(profile.slaveAz) }.getOrNull()
        } else {
            null
        }
        val el = if (profile.enableEl && s.elOnline) {
            runCatching { client.getPwm(profile.slaveEl) }.getOrNull()
        } else {
            null
        }
        _state.value = _state.value.copy(
            pwmAz = az ?: _state.value.pwmAz,
            pwmEl = el ?: _state.value.pwmEl,
            lastLog = buildString {
                append("GETPWM AZ=")
                append(az?.toString() ?: if (!s.azOnline) "off" else "?")
                if (profile.enableEl) {
                    append(" EL=")
                    append(el?.toString() ?: if (!s.elOnline) "off" else "?")
                }
            },
        )
    }

    private suspend fun refreshTempsLocked() {
        val s = _state.value
        // Nur Online-Achsen — Offline blockiert den Bus nicht
        val ambient = when {
            s.azOnline -> runCatching { client.getTempA(profile.slaveAz) }.getOrNull()
            profile.enableEl && s.elOnline -> runCatching { client.getTempA(profile.slaveEl) }.getOrNull()
            else -> null
        }
        val motorAz = if (s.azOnline) {
            runCatching { client.getTempM(profile.slaveAz) }.getOrNull()
        } else {
            null
        }
        val motorEl = if (profile.enableEl && s.elOnline) {
            runCatching { client.getTempM(profile.slaveEl) }.getOrNull()
        } else {
            null
        }
        _state.value = _state.value.copy(
            tempAmbientC = ambient ?: _state.value.tempAmbientC,
            tempMotorAzC = motorAz ?: _state.value.tempMotorAzC,
            tempMotorElC = if (profile.enableEl) {
                motorEl ?: _state.value.tempMotorElC
            } else {
                null
            },
            lastLog = buildString {
                append("TEMP Außen=")
                append(ambient?.let { "%.1f".format(it) } ?: "?")
                append(" MotorAZ=")
                append(motorAz?.let { "%.1f".format(it) } ?: if (!s.azOnline) "off" else "?")
                if (profile.enableEl) {
                    append(" MotorEL=")
                    append(motorEl?.let { "%.1f".format(it) } ?: if (!s.elOnline) "off" else "?")
                }
            },
        )
    }

    private suspend fun refreshWarningsLocked() {
        val s = _state.value
        val azW = if (s.azOnline) {
            runCatching { client.getWarn(profile.slaveAz) }.getOrNull()
        } else {
            null
        }
        val elW = if (profile.enableEl && s.elOnline) {
            runCatching { client.getWarn(profile.slaveEl) }.getOrNull()
        } else {
            null
        }
        val next = _state.value.copy(
            azWarnIds = azW ?: _state.value.azWarnIds,
            elWarnIds = elW ?: _state.value.elWarnIds,
        )
        _state.value = next.copy(
            statusText = statusTextFor(
                next.azOnline,
                next.elOnline,
                next.azReferenced,
                next.elReferenced,
                next,
            ),
            lastLog = buildString {
                append("GETWARN AZ=")
                append(azW?.joinToString(";")?.ifBlank { "0" } ?: "?")
                if (profile.enableEl) {
                    append(" EL=")
                    append(elW?.joinToString(";")?.ifBlank { "0" } ?: "?")
                }
            },
        )
    }

    /** Direkt nach GETREF: Ist-Winkel holen, damit die Nadel nicht erst nach Temp/Wind kommt. */
    private suspend fun fetchStartupPositionsLocked() {
        val cur = _state.value
        if (cur.azOnline) {
            val az = runCatching {
                client.getPosDg(profile.slaveAz, timeoutMs = POS_TIMEOUT_ONLINE_MS)
            }.getOrNull()
            if (az != null) {
                azFailStreak = 0
                lastOkPollMs = System.currentTimeMillis()
                val nowMs = SystemClock.elapsedRealtime()
                azSmoother.setDynamic(false)
                azSmoother.updateSample(az.toFloat(), nowMs = nowMs, expectedPeriodS = 0.5f)
                _state.value = _state.value.copy(
                    azDeg = az,
                    azSmoothDeg = azSmoother.current()?.toDouble() ?: az,
                )
            }
        }
        if (profile.enableEl && _state.value.elOnline) {
            val el = runCatching {
                client.getPosDg(profile.slaveEl, timeoutMs = POS_TIMEOUT_ONLINE_MS)
            }.getOrNull()
            if (el != null) {
                elFailStreak = 0
                lastOkPollMs = System.currentTimeMillis()
                val nowMs = SystemClock.elapsedRealtime()
                elSmoother.setDynamic(false)
                elSmoother.updateSample(el.toFloat(), nowMs = nowMs, expectedPeriodS = 0.5f)
                _state.value = _state.value.copy(
                    elDeg = el,
                    elSmoothDeg = elSmoother.current()?.toDouble() ?: el,
                )
            }
        }
    }

    private suspend fun refreshReferenceLocked() {
        val before = _state.value
        val nowWall = SystemClock.elapsedRealtime()
        val azRef = if (before.azOnline || nowWall >= nextAzProbeMs) {
            runCatching {
                client.getRef(
                    profile.slaveAz,
                    timeoutMs = if (before.azOnline || azFailStreak == 0) {
                        POS_TIMEOUT_ONLINE_MS
                    } else {
                        POS_TIMEOUT_OFFLINE_MS
                    },
                )
            }.getOrNull()
        } else {
            null
        }
        val elRef = if (!profile.enableEl) {
            1
        } else if (before.elOnline || nowWall >= nextElProbeMs) {
            runCatching {
                client.getRef(
                    profile.slaveEl,
                    timeoutMs = if (before.elOnline || elFailStreak == 0) {
                        POS_TIMEOUT_ONLINE_MS
                    } else {
                        POS_TIMEOUT_OFFLINE_MS
                    },
                )
            }.getOrNull()
        } else {
            null
        }

        // Timeout = offline; Antwort 0/1 = online
        val azIsOnline = azRef != null
        val elIsOnline = if (!profile.enableEl) false else elRef != null
        if (azRef == null && (before.azOnline || nowWall >= nextAzProbeMs)) {
            azFailStreak++
            if (azFailStreak >= FAIL_STREAK_OFFLINE) nextAzProbeMs = nowWall + OFFLINE_RETRY_MS
        } else if (azRef != null) {
            azFailStreak = 0
        }
        if (profile.enableEl) {
            if (elRef == null && (before.elOnline || nowWall >= nextElProbeMs)) {
                elFailStreak++
                if (elFailStreak >= FAIL_STREAK_OFFLINE) nextElProbeMs = nowWall + OFFLINE_RETRY_MS
            } else if (elRef != null) {
                elFailStreak = 0
            }
        }

        val azOk = azRef == 1
        val elOk = if (!profile.enableEl) true else elRef == 1
        if (azIsOnline || elIsOnline) lastOkPollMs = System.currentTimeMillis()

        val cur = _state.value
        _state.value = cur.copy(
            azOnline = azIsOnline,
            elOnline = if (profile.enableEl) elIsOnline else false,
            azReferenced = if (azIsOnline) azOk else false,
            elReferenced = if (!profile.enableEl) true else if (elIsOnline) elOk else false,
            azHoming = false,
            elHoming = false,
            azDeg = if (azIsOnline) cur.azDeg else null,
            elDeg = if (profile.enableEl && elIsOnline) cur.elDeg else null,
            azSmoothDeg = if (azIsOnline) cur.azSmoothDeg else null,
            elSmoothDeg = if (profile.enableEl && elIsOnline) cur.elSmoothDeg else null,
            statusText = statusTextFor(azIsOnline, elIsOnline, azOk, elOk, cur.copy(azHoming = false, elHoming = false)),
            lastLog = buildString {
                append("GETREF AZ=")
                append(azRef?.toString() ?: "NA")
                if (profile.enableEl) {
                    append(" EL=")
                    append(elRef?.toString() ?: "NA")
                }
            },
        )
        if (!azIsOnline) azSmoother.clear()
        if (profile.enableEl && !elIsOnline) elSmoother.clear()
    }

    private suspend fun pollReferenceLocked() {
        val before = _state.value
        val nowWall = SystemClock.elapsedRealtime()
        val azHomingActive = before.azHoming
        val elHomingActive = before.elHoming

        var azRef: Int? = null
        var elRef: Int? = if (!profile.enableEl) 1 else null

        // Offline-Achsen hier NICHT pollen — nur probeOfflineAxes (5 s, Idle).
        // Homing-Achse zuerst; Gegenachse nur wenn online (Keepalive).
        when {
            elHomingActive && !azHomingActive -> {
                elRef = runCatching {
                    client.getRef(profile.slaveEl, timeoutMs = POS_TIMEOUT_ONLINE_MS)
                }.getOrNull()
                if (before.azOnline) {
                    azRef = runCatching {
                        client.getRef(profile.slaveAz, timeoutMs = SIBLING_KEEPALIVE_TIMEOUT_MS)
                    }.getOrNull()
                }
            }
            azHomingActive && !elHomingActive -> {
                azRef = runCatching {
                    client.getRef(profile.slaveAz, timeoutMs = POS_TIMEOUT_ONLINE_MS)
                }.getOrNull()
                if (profile.enableEl && before.elOnline) {
                    elRef = runCatching {
                        client.getRef(profile.slaveEl, timeoutMs = SIBLING_KEEPALIVE_TIMEOUT_MS)
                    }.getOrNull()
                }
            }
            else -> {
                // Idle-GETREF: nur Online-Achsen
                if (before.azOnline || azHomingActive) {
                    azRef = runCatching {
                        client.getRef(profile.slaveAz, timeoutMs = POS_TIMEOUT_ONLINE_MS)
                    }.getOrNull()
                }
                if (profile.enableEl && (before.elOnline || elHomingActive)) {
                    elRef = runCatching {
                        client.getRef(profile.slaveEl, timeoutMs = POS_TIMEOUT_ONLINE_MS)
                    }.getOrNull()
                }
            }
        }

        // Timeout während Homing ≠ Offline (sonst stoppt GETREF → Deadman)
        var azOnlineOut = before.azOnline
        var elOnlineOut = before.elOnline

        if (before.azOnline || azHomingActive) {
            if (azRef != null) {
                azFailStreak = 0
                azOnlineOut = true
            } else if (azHomingActive) {
                azOnlineOut = true
            } else {
                azFailStreak++
                if (azFailStreak >= FAIL_STREAK_OFFLINE) {
                    azOnlineOut = false
                    nextAzProbeMs = nowWall + OFFLINE_RETRY_MS
                }
            }
        } else {
            azOnlineOut = false
        }

        if (profile.enableEl) {
            if (before.elOnline || elHomingActive) {
                if (elRef != null) {
                    elFailStreak = 0
                    elOnlineOut = true
                } else if (elHomingActive) {
                    elOnlineOut = true
                } else {
                    elFailStreak++
                    if (elFailStreak >= FAIL_STREAK_OFFLINE) {
                        elOnlineOut = false
                        nextElProbeMs = nowWall + OFFLINE_RETRY_MS
                    }
                }
            } else {
                elOnlineOut = false
            }
        } else {
            elOnlineOut = false
        }

        val azOk = azRef == 1
        val elOk = if (!profile.enableEl) true else elRef == 1
        val cur = _state.value
        val azHoming = when {
            !cur.azHoming -> false
            azRef == 1 -> false
            else -> true
        }
        val elHoming = when {
            !cur.elHoming -> false
            elRef == 1 -> false
            else -> true
        }
        if (azRef != null || elRef != null) lastOkPollMs = System.currentTimeMillis()

        val azReferenced = when {
            azRef != null -> azOk
            azHomingActive -> false
            !azOnlineOut -> false
            else -> cur.azReferenced
        }
        val elReferenced = when {
            !profile.enableEl -> true
            elRef != null -> elOk
            elHomingActive -> false
            !elOnlineOut -> false
            else -> cur.elReferenced
        }

        _state.value = cur.copy(
            azOnline = azOnlineOut,
            elOnline = if (profile.enableEl) elOnlineOut else false,
            azReferenced = azReferenced,
            elReferenced = elReferenced,
            azHoming = azHoming,
            elHoming = elHoming,
            azDeg = if (azOnlineOut) cur.azDeg else null,
            elDeg = if (profile.enableEl && elOnlineOut) cur.elDeg else null,
            azSmoothDeg = if (azOnlineOut) cur.azSmoothDeg else null,
            elSmoothDeg = if (profile.enableEl && elOnlineOut) cur.elSmoothDeg else null,
            moving = if (
                (!azReferenced && azOnlineOut || !elReferenced && elOnlineOut) &&
                !azHoming && !elHoming && cur.referenced
            ) {
                false
            } else {
                cur.moving
            },
            statusText = statusTextFor(
                azOnlineOut,
                elOnlineOut,
                azReferenced,
                elReferenced,
                cur.copy(azHoming = azHoming, elHoming = elHoming),
            ),
            lastLog = buildString {
                append("GETREF AZ=")
                append(azRef?.toString() ?: if (azHomingActive) "…" else if (!before.azOnline) "off" else "NA")
                if (profile.enableEl) {
                    append(" EL=")
                    append(elRef?.toString() ?: if (elHomingActive) "…" else if (!before.elOnline) "off" else "NA")
                }
            },
        )
        if (!azOnlineOut && !azHomingActive) azSmoother.clear()
        if (profile.enableEl && !elOnlineOut && !elHomingActive) elSmoother.clear()
    }

    /** Offline = nur NA in der Karte, keine Banner-Meldung. */
    private fun statusTextFor(
        azOnline: Boolean,
        elOnline: Boolean,
        azOk: Boolean,
        elOk: Boolean,
        s: RotorLiveState,
    ): String {
        val fault = faultStatusText(s)
        if (fault.isNotBlank() && s.hasFault) return fault
        if (s.azHoming && s.elHoming) return "Homing AZ/EL…"
        if (s.azHoming) return "Homing AZ…"
        if (s.elHoming) return "Homing EL…"
        if (fault.isNotBlank()) return fault // Warnungen nach Homing-Hinweis
        val azNeed = azOnline && !azOk
        val elNeed = profile.enableEl && elOnline && !elOk
        return when {
            azNeed && elNeed -> "AZ/EL nicht referenziert – HOME nötig"
            azNeed -> "AZ nicht referenziert – HOME nötig"
            elNeed -> "EL nicht referenziert – HOME nötig"
            else -> ""
        }
    }

    private fun resetAxisHealth() {
        azFailStreak = 0
        elFailStreak = 0
        nextAzProbeMs = 0L
        nextElProbeMs = 0L
        lastOkPollMs = 0L
    }

    private fun ensureConnected() {
        check(client.isConnected) { "Nicht verbunden" }
    }

    /** GETROTORTYPE an AZ/EL — Anzeige zuerst aus Cache, Bus bestätigt/aktualisiert. */
    private suspend fun queryRotorTypesLocked() {
        val azType = runCatching {
            client.getRotorType(profile.slaveAz, timeoutMs = POS_TIMEOUT_ONLINE_MS)
        }.getOrNull()
        val elType = if (profile.enableEl) {
            runCatching {
                client.getRotorType(profile.slaveEl, timeoutMs = POS_TIMEOUT_ONLINE_MS)
            }.getOrNull()
        } else {
            null
        }
        // Ohne Antwort: Cache behalten (kein Flackern auf Default 90°)
        val resolvedElType = if (profile.enableEl) {
            elType ?: profile.cachedElRotorType()
        } else {
            2
        }
        val elMax = elMaxFromType(resolvedElType)
        rememberElRotorTypeIfChanged(elType)
        _state.value = _state.value.copy(
            azRotorType = azType ?: 1,
            elRotorType = resolvedElType,
            elMaxDeg = if (profile.enableEl) elMax else 90.0,
            lastLog = buildString {
                append("GETROTORTYPE AZ=")
                append(azType?.toString() ?: "?→1")
                if (profile.enableEl) {
                    append(" EL=")
                    append(elType?.toString() ?: "cache→$resolvedElType")
                    append(" max=${elMax.toInt()}°")
                }
            },
        )
    }

    private fun rememberElRotorTypeIfChanged(elType: Int?) {
        if (!profile.enableEl || elType == null) return
        val t = elType.coerceIn(2, 3)
        if (t == profile.lastElRotorType) return
        profile = profile.copy(lastElRotorType = t)
        onElRotorTypeCached?.invoke(profile)
    }

    private fun elMaxFromType(type: Int?): Double = when (type) {
        3 -> 180.0
        else -> 90.0 // 2, null, unbekannt → 90°
    }

    private fun clampEl(deg: Double): Double =
        deg.coerceIn(0.0, _state.value.elMaxDeg.coerceIn(90.0, 180.0))

    private fun wrapAz(deg: Double): Double = AntennaMath.wrap360(deg)
}
