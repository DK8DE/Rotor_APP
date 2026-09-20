package de.dk8de.rotorapp.rotor

import android.content.Context
import de.dk8de.rotorapp.R

/**
 * Fehler- und Warncodes laut RotorController_RS485.md (§8 Warnungen, §9 Fehler).
 * Anzeigetexte über String-Ressourcen (DE/EN).
 */
object RotorCodes {

    data class Info(
        val name: String,
        val meaningRes: Int,
    )

    private val ERRORS: Map<Int, Info> = mapOf(
        0 to Info("SE_NONE", R.string.err_none),
        10 to Info("SE_TIMEOUT", R.string.err_timeout),
        11 to Info("SE_ENDSTOP", R.string.err_endstop),
        12 to Info("SE_NSTOP_CMD", R.string.err_nstop),
        15 to Info("SE_IS_HARD", R.string.err_hard_limit),
        16 to Info("SE_STALL", R.string.err_stall),
        17 to Info("SE_HOME_FAIL", R.string.err_home_fail),
        18 to Info("SE_POS_TIMEOUT", R.string.err_pos_timeout),
    )

    private val WARNINGS: Map<Int, Info> = mapOf(
        0 to Info("SW_NONE", R.string.warn_none),
        1 to Info("SW_IS_SOFT", R.string.warn_soft_limit),
        2 to Info("SW_WIND_GUST", R.string.warn_wind_gust),
        3 to Info("SW_DRAG_INCREASE", R.string.warn_drag_increase),
        4 to Info("SW_DRAG_DECREASE", R.string.warn_drag_decrease),
        5 to Info("SW_TEMP_AMBIENT_HIGH", R.string.warn_temp_ambient),
        6 to Info("SW_TEMP_MOTOR_HIGH", R.string.warn_temp_motor),
    )

    fun errorInfo(code: Int): Info =
        ERRORS[code] ?: Info("SE_UNKNOWN", R.string.err_unknown)

    fun warningInfo(id: Int): Info =
        WARNINGS[id] ?: Info("SW_UNKNOWN", R.string.warn_unknown)

    fun formatError(context: Context, axis: String, code: Int): String {
        if (code == 0) return ""
        val i = errorInfo(code)
        val meaning = if (ERRORS.containsKey(code)) {
            context.getString(i.meaningRes)
        } else {
            context.getString(R.string.err_unknown, code)
        }
        return context.getString(R.string.rotor_error_line, axis, code, i.name, meaning)
    }

    fun formatWarnings(context: Context, axis: String, ids: List<Int>): String {
        val active = ids.filter { it != 0 }.distinct().sorted()
        if (active.isEmpty()) return ""
        val parts = active.map { id ->
            val i = warningInfo(id)
            "$id ${i.name}"
        }
        return context.getString(R.string.rotor_warning_line, axis, parts.joinToString(", "))
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
