package de.dk8de.rotorapp.rotor

/**
 * Fehler- und Warncodes laut RotorController_RS485.md (§8 Warnungen, §9 Fehler).
 */
object RotorCodes {

    data class Info(
        val name: String,
        val meaning: String,
        val action: String = "",
    )

    private val ERRORS: Map<Int, Info> = mapOf(
        0 to Info("SE_NONE", "Kein Fehler"),
        10 to Info(
            "SE_TIMEOUT",
            "Deadman/Keepalive Timeout",
            "Master sendet zu lange keine Befehle während Bewegung — SETREF quittieren",
        ),
        11 to Info(
            "SE_ENDSTOP",
            "Endschalter blockiert Fahrtrichtung",
            "Richtung/Endschalter prüfen, dann SETREF",
        ),
        12 to Info(
            "SE_NSTOP_CMD",
            "NSTOP (Not-Aus) per RS485",
            "Ursache im Master, dann SETREF",
        ),
        15 to Info(
            "SE_IS_HARD",
            "Strom-Hardlimit",
            "Mechanik / IMAX prüfen, dann SETREF",
        ),
        16 to Info(
            "SE_STALL",
            "Stall: Encoder bewegt sich nicht",
            "Mechanik / MINPWM / STALLTIMEOUT, dann SETREF",
        ),
        17 to Info(
            "SE_HOME_FAIL",
            "Homing fehlgeschlagen",
            "Endschalter / HOMETIMEOUT prüfen, dann SETREF",
        ),
        18 to Info(
            "SE_POS_TIMEOUT",
            "Positionsfahrt: Ziel nicht rechtzeitig erreicht",
            "SETPOSTIMEOUT / Mechanik prüfen, dann SETREF",
        ),
    )

    private val WARNINGS: Map<Int, Info> = mapOf(
        0 to Info("SW_NONE", "Keine Warnung"),
        1 to Info("SW_IS_SOFT", "Strom-Warnung (Soft-Limit)", "Limits / Mechanik prüfen"),
        2 to Info("SW_WIND_GUST", "Windböe / kurz erhöhte Last", "Bei Dauer: Schwellwerte prüfen"),
        3 to Info("SW_DRAG_INCREASE", "Gleichmäßig mehr Reibung", "Kälte / Getriebe prüfen"),
        4 to Info("SW_DRAG_DECREASE", "Gleichmäßig weniger Reibung", "Kann auf fehlende Last hinweisen"),
        5 to Info("SW_TEMP_AMBIENT_HIGH", "Umgebung zu warm", "Belüftung / Warnschwelle"),
        6 to Info("SW_TEMP_MOTOR_HIGH", "Motor zu warm", "Pausen / Last reduzieren"),
    )

    fun errorInfo(code: Int): Info =
        ERRORS[code] ?: Info("SE_UNKNOWN", "Unbekannter Fehler ($code)", "SETREF quittieren")

    fun warningInfo(id: Int): Info =
        WARNINGS[id] ?: Info("SW_UNKNOWN", "Unbekannte Warnung ($id)")

    fun formatError(axis: String, code: Int): String {
        if (code == 0) return ""
        val i = errorInfo(code)
        return "$axis Fehler $code ${i.name}: ${i.meaning}"
    }

    fun formatWarnings(axis: String, ids: List<Int>): String {
        val active = ids.filter { it != 0 }.distinct().sorted()
        if (active.isEmpty()) return ""
        val parts = active.map { id ->
            val i = warningInfo(id)
            "$id ${i.name}"
        }
        return "$axis Warnung: ${parts.joinToString(", ")}"
    }

    /** ACK_GETWARN params: `0` oder `id;id;...` */
    fun parseWarnIds(params: String): List<Int> {
        val raw = params.trim()
        if (raw.isEmpty() || raw == "0") return emptyList()
        return raw.split(';', ',', ' ')
            .mapNotNull { it.trim().replace(',', '.').toDoubleOrNull()?.toInt() }
            .filter { it != 0 }
            .distinct()
            .sorted()
    }
}
