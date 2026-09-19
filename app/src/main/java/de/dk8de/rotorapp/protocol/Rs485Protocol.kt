package de.dk8de.rotorapp.protocol

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * RS485 ASCII protocol: #SRC:DST:CMD:PARAMS:CS$
 * CS = SRC + DST + last number in PARAMS (0 if none).
 */
data class Telegram(
    val src: Int,
    val dst: Int,
    val cmd: String,
    val params: String,
    val cs: Double,
    val ok: Boolean,
)

object Rs485Protocol {
    private val NUM_RE = Regex("""[-+]?\d+(?:[.,]\d+)?""")

    const val BROADCAST_DST = 255

    fun lastNumber(params: String): Double {
        val matches = NUM_RE.findAll(params).map { it.value }.toList()
        if (matches.isEmpty()) return 0.0
        return matches.last().replace(',', '.').toDouble()
    }

    fun calcChecksum(src: Int, dst: Int, params: String): Double =
        src.toDouble() + dst.toDouble() + lastNumber(params)

    fun formatCs(cs: Double): String {
        if (abs(cs - cs.roundToInt()) < 0.005) {
            return cs.roundToInt().toString()
        }
        var s = "%.2f".format(java.util.Locale.US, cs).replace('.', ',')
        if (',' in s) {
            s = s.trimEnd('0').trimEnd(',')
        }
        return s
    }

    fun formatDeg(deg: Double): String =
        "%.2f".format(java.util.Locale.US, deg).replace('.', ',')

    fun build(src: Int, dst: Int, cmd: String, params: String): String {
        val cs = calcChecksum(src, dst, params)
        return "#$src:$dst:$cmd:$params:${formatCs(cs)}$"
    }

    fun parse(line: String): Telegram? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("#") || !trimmed.endsWith("$")) return null
        val body = trimmed.substring(1, trimmed.length - 1)
        val parts = body.split(":")
        if (parts.size < 5) return null
        val src = parts[0].toIntOrNull() ?: return null
        val dst = parts[1].toIntOrNull() ?: return null
        val cmd = parts[2].trim()
        val params = parts.subList(3, parts.size - 1).joinToString(":").trim()
        val cs = parts.last().trim().replace(',', '.').toDoubleOrNull() ?: return null
        val expected = calcChecksum(src, dst, params)
        val ok = abs(cs - expected) <= 0.02
        return Telegram(src, dst, cmd, params, cs, ok)
    }

    fun parseDegree(params: String): Double? {
        val first = params.split(';', ':').firstOrNull()?.trim().orEmpty()
        if (first.isEmpty()) return null
        return first.replace(',', '.').toDoubleOrNull()
    }

    /**
     * SETPOSCC-Payload: Vorschauwinkel [, optional `;Rotor-Bus-ID`].
     * `151,30` → (151.3, null); `151,30;20` → (151.3, 20)
     */
    fun parseSetPosCc(params: String): Pair<Double?, Int?> {
        val raw = params.trim()
        if (raw.isEmpty()) return null to null
        val parts = raw.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size >= 2) {
            val tail = parts.last().replace(" ", "")
            if (tail.all { it.isDigit() }) {
                val rid = tail.toIntOrNull()
                if (rid != null && rid in 1..254) {
                    val angle = parseDegree(parts.dropLast(1).joinToString(";"))
                    return angle to rid
                }
            }
        }
        return parseDegree(raw) to null
    }
}
