package de.dk8de.rotorapp.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.dk8de.rotorapp.data.PositionFavorite
import de.dk8de.rotorapp.data.ProfileStore
import de.dk8de.rotorapp.data.RotorProfile
import de.dk8de.rotorapp.data.UiDisplayPrefs
import de.dk8de.rotorapp.rotor.AntennaSlot
import de.dk8de.rotorapp.rotor.RotorAxis
import de.dk8de.rotorapp.rotor.RotorLiveState
import de.dk8de.rotorapp.rotor.RotorRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class AppUiState(
    val profiles: List<RotorProfile> = emptyList(),
    val activeProfile: RotorProfile? = null,
    val rotor: RotorLiveState = RotorLiveState(),
    val hostDraft: String = "192.168.0.246",
    val portDraft: String = "8886",
    val displayPrefs: UiDisplayPrefs = UiDisplayPrefs(),
    val favorites: List<PositionFavorite> = emptyList(),
) {
    val showBeamOverlay: Boolean get() = displayPrefs.showBeamOverlay
    val showStromRing: Boolean get() = displayPrefs.showStromRing
    val showDwellRing: Boolean get() = displayPrefs.showDwellRing
    val dwellFullMinutes: Float get() = displayPrefs.dwellFullMinutes
    val dwellSectors: Int get() = displayPrefs.dwellSectors
    val windDirMode: String
        get() = when {
            activeProfile?.windDirMode.equals("to", ignoreCase = true) -> "to"
            else -> "from"
        }
    val stromHeatmapScale: de.dk8de.rotorapp.ui.theme.HeatmapScale?
        get() = displayPrefs.stromHeatmapScale()
}

class RotorViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ProfileStore(app)
    private val repo = RotorRepository(app)

    private val _hostDraft = MutableStateFlow("192.168.0.246")
    private val _portDraft = MutableStateFlow("8886")

    val uiState: StateFlow<AppUiState> = combine(
        store.profiles,
        store.activeProfileId,
        repo.state,
        combine(_hostDraft, _portDraft, store.uiDisplayPrefs, store.positionFavorites) { host, port, prefs, favs ->
            arrayOf(host, port, prefs, favs)
        },
    ) { profiles, activeId, rotor, drafts ->
        val active = profiles.find { it.id == activeId } ?: profiles.firstOrNull()
        @Suppress("UNCHECKED_CAST")
        AppUiState(
            profiles = profiles,
            activeProfile = active,
            rotor = rotor,
            hostDraft = drafts[0] as String,
            portDraft = drafts[1] as String,
            displayPrefs = drafts[2] as UiDisplayPrefs,
            favorites = drafts[3] as List<PositionFavorite>,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AppUiState())

    private var pollJob: Job? = null
    private var displayJob: Job? = null
    private var sniffJob: Job? = null
    private var statsJob: Job? = null
    private var connectJob: Job? = null
    /** Inhalt inkl. IDs — bei Profil-Edit neu einlesen, nicht nur bei ID-Wechsel. */
    private var appliedSignature: String? = null
    private var autoConnectAttempted = false

    private val _startupReady = MutableStateFlow(false)
    val startupReady: StateFlow<Boolean> = _startupReady

    init {
        // Splash sofort beenden, sobald Profile da sind — nicht auf TCP warten
        viewModelScope.launch {
            runCatching { store.profiles.first() }
            _startupReady.value = true
        }
        viewModelScope.launch {
            delay(400)
            _startupReady.value = true
        }
        repo.onElRotorTypeCached = { cached ->
            viewModelScope.launch {
                runCatching { store.upsert(cached, makeActive = false) }
            }
        }
        repo.onWindEnableSynced = { synced ->
            viewModelScope.launch {
                runCatching { store.upsert(synced, makeActive = false) }
            }
        }
        viewModelScope.launch {
            store.uiDisplayPrefs.collect { prefs ->
                repo.setDisplayRingPrefs(prefs.showStromRing, prefs.showDwellRing)
                repo.setDwellSectorCount(prefs.dwellSectors)
            }
        }
        viewModelScope.launch {
            uiState.collect { state ->
                val p = state.activeProfile ?: return@collect
                // wantWindPoll kommt vom Rotor (GET) bzw. nach SET — nicht aus altem Profil-Haken
                val sig = profileSignature(p)
                if (sig != appliedSignature) {
                    appliedSignature = sig
                    _hostDraft.value = p.host
                    _portDraft.value = p.port.toString()
                    autoConnectAttempted = true
                    stopPolling()
                    connectJob?.cancel()
                    // Verbindung im Hintergrund — UI bleibt offen
                    connectJob = viewModelScope.launch {
                        runCatching { repo.applyProfile(p, reconnect = true) }
                        if (repo.state.value.connected) {
                            // Fallback falls Connect-Bootstrap Antennen verpasst hat
                            val needAnt = repo.state.value.antennas.none { it.openingDeg > 0.5 }
                            if (needAnt) {
                                runCatching { repo.refreshAntennas() }
                            }
                            startPolling()
                        }
                    }
                }
            }
        }
    }

    private fun profileSignature(p: RotorProfile): String =
        listOf(
            p.id,
            p.host,
            p.port,
            p.masterId,
            p.slaveAz,
            p.enableEl,
            p.slaveEl,
            p.controllerId,
            // enableWind bewusst nicht: wird per GETWINDENABLE vom Rotor gesetzt
        ).joinToString("|")

    private suspend fun tryAutoConnect(profile: RotorProfile) {
        try {
            val ok = runCatching {
                repo.connect(profile.host, profile.port, quietFail = true)
            }.getOrDefault(false)
            if (ok) startPolling()
        } finally {
            _startupReady.value = true
        }
    }

    fun startHoming(axis: RotorAxis) {
        viewModelScope.launch {
            repo.beginUserCommand()
            val ok = try {
                runCatching { repo.startHoming(axis) }.getOrDefault(false)
            } finally {
                repo.endUserCommand()
            }
            if (ok) {
                // Poll-Loop sofort auf Homing-Zweig (GETREF zyklisch)
                stopPolling()
                startPolling()
            }
        }
    }

    private var motionJob: Job? = null
    private var motionGen: Int = 0

    fun setAzimuth(deg: Double) {
        val gen = ++motionGen
        repo.beginUserCommand()
        motionJob?.cancel()
        motionJob = viewModelScope.launch {
            try {
                runCatching { repo.setAzimuth(deg) }
            } finally {
                if (gen == motionGen) repo.endUserCommand()
            }
            startPolling()
        }
    }

    /** Parken: GETHOMEPOS → SETPOSDG (AZ; mit EL wenn aktiv). */
    fun parkAzimuth() {
        val gen = ++motionGen
        repo.beginUserCommand()
        motionJob?.cancel()
        motionJob = viewModelScope.launch {
            try {
                runCatching { repo.parkAzimuth() }
            } finally {
                if (gen == motionGen) repo.endUserCommand()
            }
            startPolling()
        }
    }

    fun setElevation(deg: Double) {
        val gen = ++motionGen
        repo.beginUserCommand()
        motionJob?.cancel()
        motionJob = viewModelScope.launch {
            try {
                runCatching { repo.setElevation(deg) }
            } finally {
                if (gen == motionGen) repo.endUserCommand()
            }
            startPolling()
        }
    }

    fun stopAxis(axis: RotorAxis) {
        val gen = ++motionGen
        repo.beginUserCommand()
        motionJob?.cancel()
        motionJob = viewModelScope.launch {
            try {
                runCatching { repo.stopAxis(axis) }
            } finally {
                if (gen == motionGen) repo.endUserCommand()
            }
        }
    }

    private var pwmJobs = mutableMapOf<RotorAxis, Job>()

    fun setPwm(axis: RotorAxis, percent: Int) {
        val p = percent.coerceIn(30, 100)
        pwmJobs[axis]?.cancel()
        pwmJobs[axis] = viewModelScope.launch {
            // Kurzes Coalesce, damit beim Ziehen nicht jeder Frame blockiert
            delay(40)
            runCatching { repo.setPwm(axis, p) }
        }
    }

    fun refreshPwm() {
        viewModelScope.launch {
            if (repo.state.value.connected) {
                runCatching { repo.refreshPwm() }
            }
        }
    }

    fun refreshTemps() {
        viewModelScope.launch {
            if (repo.state.value.connected) {
                runCatching { repo.refreshTemps() }
            }
        }
    }

    fun refreshAntennas() {
        viewModelScope.launch {
            if (repo.state.value.connected) {
                runCatching { repo.refreshAntennas() }
            }
        }
    }

    fun selectAntenna(slot: Int) {
        viewModelScope.launch {
            runCatching { repo.selectAntenna(slot) }
        }
    }

    fun saveAntenna(slot: Int, data: AntennaSlot) {
        viewModelScope.launch {
            if (repo.state.value.connected) {
                runCatching { repo.writeAntennaSlot(slot, data) }
            }
        }
    }

    fun setShowBeamOverlay(enabled: Boolean) {
        viewModelScope.launch {
            store.setShowBeamOverlay(enabled)
        }
    }

    fun setShowStromRing(enabled: Boolean) {
        viewModelScope.launch {
            store.setShowStromRing(enabled)
        }
    }

    fun setShowDwellRing(enabled: Boolean) {
        viewModelScope.launch {
            store.setShowDwellRing(enabled)
        }
    }

    fun setDwellFullMinutes(minutes: Float) {
        viewModelScope.launch {
            store.setDwellFullMinutes(minutes)
        }
    }

    fun setDwellSectors(sectors: Int) {
        viewModelScope.launch {
            store.setDwellSectors(sectors)
        }
    }

    fun setHeatmapCustom(enabled: Boolean) {
        viewModelScope.launch {
            store.setHeatmapCustom(enabled)
        }
    }

    fun setHeatmapScale(
        custom: Boolean,
        thrBlue: Int,
        normMin: Int,
        normMax: Int,
        thrRed: Int,
    ) {
        viewModelScope.launch {
            store.setHeatmapScale(custom, thrBlue, normMin, normMax, thrRed)
        }
    }

    fun setLocation(lat: Double, lon: Double, locator: String = "") {
        viewModelScope.launch {
            store.setLocation(lat, lon, locator)
        }
    }

    /** Aktuelle Ist-Position als Favorit (fehlendes AZ → 0). */
    fun saveFavorite(name: String) {
        val s = uiState.value
        val az = s.rotor.displayAz(s.rotor.azSmoothDeg ?: s.rotor.azDeg) ?: 0.0
        val el = if (s.activeProfile?.enableEl == true) {
            (s.rotor.elSmoothDeg ?: s.rotor.elDeg) ?: 0.0
        } else {
            0.0
        }
        viewModelScope.launch {
            store.addPositionFavorite(name, az, el)
        }
    }

    fun deleteFavorite(id: String) {
        viewModelScope.launch {
            store.deletePositionFavorite(id)
        }
    }

    fun goFavorite(id: String) {
        val fav = uiState.value.favorites.find { it.id == id } ?: return
        setAzimuth(fav.azDeg)
        if (uiState.value.activeProfile?.enableEl == true) {
            setElevation(fav.elDeg)
        }
    }

    /** Schwellen aus aktuellen Strom-Bins (wie Bridge „aus Kalibrierung“). */
    fun applyHeatmapFromCurrentBins() {
        val az = repo.state.value.stromBins36.orEmpty()
        val el = repo.state.value.stromBinsEl36.orEmpty()
        val usable = (az + el).filter { it > 0 }
        if (usable.isEmpty()) return
        val mn = usable.minOrNull() ?: return
        val mx = usable.maxOrNull() ?: return
        val margin = 50
        viewModelScope.launch {
            store.setHeatmapScale(
                custom = true,
                thrBlue = (mn - margin).coerceAtLeast(0),
                normMin = mn,
                normMax = mx,
                thrRed = (mx + margin).coerceAtMost(65535),
            )
        }
    }

    fun resetDwellTimes() {
        repo.resetDwellTimes()
    }

    fun resetAccBins() {
        viewModelScope.launch {
            runCatching { repo.resetAccBins() }
        }
    }

    /** App wieder im Vordergrund: ggf. neu verbinden + Polling — kein zweites Full-Bootstrap. */
    fun onForeground() {
        viewModelScope.launch {
            val already = repo.state.value.connected
            val ok = runCatching {
                repo.ensureConnectedOrReconnect(staleAfterMs = if (already) 15_000L else 0L)
            }.getOrDefault(false)
            // Nur wenn wir nicht gerade frisch gebootstrappt haben (Profil-Collect)
            if (ok && !already) {
                runCatching { repo.pollReference() }
                runCatching { repo.refreshPwm() }
                runCatching { repo.refreshTemps() }
                runCatching { repo.refreshWind(force = true) }
                runCatching { repo.refreshAntennas() }
            }
            startPolling()
        }
    }

    /** App im Hintergrund: kein Bus-Polling/Sniff mehr — Bus bleibt frei. */
    fun onBackground() {
        stopPolling()
    }

    fun activateProfile(id: String) {
        viewModelScope.launch {
            stopPolling()
            // Signatur zurücksetzen → Collect lädt Startup-Daten neu
            appliedSignature = null
            store.setActive(id)
        }
    }

    fun saveProfile(profile: RotorProfile, makeActive: Boolean = true) {
        viewModelScope.launch {
            stopPolling()
            if (makeActive) {
                // Vor upsert setzen, damit Collect nicht parallel nochmal verbindet
                appliedSignature = profileSignature(profile)
                _hostDraft.value = profile.host
                _portDraft.value = profile.port.toString()
                // Haken → SETWINDENABLE beim Connect (sonst nur GET)
                repo.requestWindEnableWrite(profile.enableWind)
            }
            store.upsert(profile, makeActive = makeActive)
            if (makeActive) {
                runCatching { repo.applyProfile(profile, reconnect = true) }
                if (repo.state.value.connected) {
                    startPolling()
                } else {
                    tryAutoConnect(profile)
                }
                _startupReady.value = true
            }
        }
    }

    fun deleteProfile(id: String) {
        viewModelScope.launch {
            store.delete(id)
            if (appliedSignature?.startsWith("$id|") == true) {
                appliedSignature = null
            }
        }
    }

    private fun startPolling() {
        if (pollJob?.isActive != true) {
            pollJob = viewModelScope.launch {
                var lastPosPollMs = 0L
                while (isActive) {
                    val s = repo.state.value
                    when {
                        !s.connected -> {
                            runCatching { repo.ensureConnectedOrReconnect(staleAfterMs = 0L) }
                            delay(2_000L)
                        }
                        s.busPassive -> {
                            delay(200L)
                        }
                        s.homing -> {
                            runCatching { repo.pollReference() }
                            delay(200L)
                        }
                        s.moving || repo.isMotionHoldActive() || repo.needsFastPoll() -> {
                            // Bridge pos_fast ≈ 200 ms
                            runCatching { repo.pollPositions(expectedPeriodS = 0.2f) }
                            delay(80L)
                        }
                        else -> {
                            val now = System.currentTimeMillis()
                            val needPos = !s.azOnline || s.azDeg == null ||
                                (repo.activeProfile().enableEl &&
                                    (!s.elOnline || s.elDeg == null))
                            // GETPOSDG fest 1×/s — sonst nichts in diesem Job
                            val posInterval = if (needPos) 300L else 1_000L
                            if (now - lastPosPollMs >= posInterval) {
                                lastPosPollMs = System.currentTimeMillis()
                                if (needPos &&
                                    (!s.azOnline ||
                                        (repo.activeProfile().enableEl && !s.elOnline))
                                ) {
                                    runCatching { repo.probeOfflineAxes() }
                                }
                                runCatching {
                                    repo.pollPositions(
                                        expectedPeriodS = if (needPos) 0.5f else 1f,
                                    )
                                }
                            }
                            interruptiblePollDelay(100L)
                        }
                    }
                }
            }
        }
        // Temp/Wind alle 2 s, Stromring alle 4 s — eigener Job, blockiert GETPOS-Takt nicht
        if (statsJob?.isActive != true) {
            statsJob = viewModelScope.launch {
                var lastTempWindMs = 0L
                var lastAccMs = 0L
                var lastRefMs = 0L
                var wasMoving = false
                while (isActive) {
                    val s = repo.state.value
                    val busy = s.moving || s.homing || repo.isMotionHoldActive() ||
                        repo.needsFastPoll()
                    val now = System.currentTimeMillis()
                    if (s.connected && !s.busPassive) {
                        if (busy) {
                            wasMoving = true
                        } else {
                            if (wasMoving) {
                                wasMoving = false
                                runCatching { repo.refreshAccBins(force = true) }
                                lastAccMs = now
                            }
                            if (now - lastTempWindMs >= 2_000L) {
                                val tempOk = runCatching { repo.refreshTemps() }.getOrDefault(false)
                                val windOn = repo.activeProfile().enableWind ||
                                    repo.state.value.windHwEnabled
                                val windOk = if (windOn) {
                                    runCatching { repo.refreshWind() }.getOrDefault(false)
                                } else {
                                    true
                                }
                                // Nur bei echtem Bus-Zugriff weiterschalten (sonst sofort erneut)
                                if (tempOk || (windOn && windOk)) {
                                    lastTempWindMs = System.currentTimeMillis()
                                }
                            }
                            if (uiState.value.showStromRing &&
                                now - lastAccMs >= 4_000L
                            ) {
                                runCatching { repo.refreshAccBins() }
                                lastAccMs = System.currentTimeMillis()
                            }
                            if (now - lastRefMs >= 8_000L) {
                                runCatching { repo.probeOfflineAxes() }
                                runCatching { repo.pollReference() }
                                lastRefMs = System.currentTimeMillis()
                            }
                        }
                    } else {
                        wasMoving = false
                    }
                    delay(200L)
                }
            }
        }
        if (displayJob?.isActive != true) {
            displayJob = viewModelScope.launch {
                while (isActive) {
                    if (repo.state.value.connected) {
                        repo.tickDisplay()
                    }
                    delay(33L)
                }
            }
        }
        if (sniffJob?.isActive != true) {
            sniffJob = viewModelScope.launch {
                while (isActive) {
                    if (repo.state.value.connected) {
                        val passive = repo.state.value.busPassive
                        runCatching {
                            repo.sniffBus(if (passive) 80L else 40L)
                        }
                    }
                    delay(if (repo.state.value.busPassive) 30L else 50L)
                }
            }
        }
    }

    /** Idle-delay, der bei SETPOS/Fahrt sofort endet. */
    private suspend fun interruptiblePollDelay(totalMs: Long) {
        var left = totalMs
        while (left > 0) {
            if (repo.needsFastPoll()) return
            val step = minOf(50L, left)
            delay(step)
            left -= step
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        displayJob?.cancel()
        displayJob = null
        sniffJob?.cancel()
        sniffJob = null
        statsJob?.cancel()
        statsJob = null
    }

    override fun onCleared() {
        stopPolling()
        super.onCleared()
    }
}
