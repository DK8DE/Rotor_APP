package de.dk8de.rotorapp.data

import java.util.UUID

data class RotorProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Rotor",
    val host: String = "192.168.0.246",
    val port: Int = 8886,
    val masterId: Int = 7,
    val slaveAz: Int = 20,
    val enableEl: Boolean = false,
    val slaveEl: Int = 21,
    /** Bus-SRC des Display-/Zweit-Controllers (SETPOSCC), 0 = kein Filter. */
    val controllerId: Int = 2,
    /** Wind am AZ: SETWINDENABLE + Abfrage GETANEMO/GETWINDDIR. */
    val enableWind: Boolean = false,
    /**
     * Windpfeil: `"from"` = woher der Wind kommt, `"to"` = wohin er weht.
     * Nur relevant wenn [enableWind].
     */
    val windDirMode: String = "from",
    /**
     * Zuletzt gelesener EL-Rotortyp (2 = 90°, 3 = 180°).
     * Beim Start sofort für die Anzeige nutzen, damit die GUI nicht flackert.
     */
    val lastElRotorType: Int = 2,
) {
    fun summary(azElLabel: String, azOnlyLabel: String, windSuffix: String): String {
        val el = if (enableEl) azElLabel else azOnlyLabel
        val wind = if (enableWind) windSuffix else ""
        return "$name · $host:$port · $el$wind"
    }

    /** Anzeige-Maxwinkel aus Cache (ohne Bus-Abfrage). */
    fun cachedElMaxDeg(): Double = if (!enableEl) 90.0 else when (lastElRotorType) {
        3 -> 180.0
        else -> 90.0
    }

    fun cachedElRotorType(): Int = if (!enableEl) 2 else lastElRotorType.coerceIn(2, 3)
}
