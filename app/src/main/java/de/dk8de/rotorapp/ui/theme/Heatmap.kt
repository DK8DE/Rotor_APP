package de.dk8de.rotorapp.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Eigene Farb-Skala wie Bridge `(thr_blue, norm_min, norm_max, thr_red)`.
 * Nur gültig wenn blau ≤ Norm min ≤ Norm max ≤ rot.
 */
data class HeatmapScale(
    val thrBlue: Int = 0,
    val normMin: Int = 0,
    val normMax: Int = 0,
    val thrRed: Int = 0,
) {
    fun isValid(): Boolean =
        thrBlue <= normMin && normMin <= normMax && normMax <= thrRed
}

/** Mindestspanne der Auto-Skala (wie Bridge `_AUTO_HEATMAP_MIN_SPAN`). */
const val AUTO_HEATMAP_MIN_SPAN = 32

/**
 * Auto-Bereich symmetrisch um die Mitte auf mindestens [minSpan] aufweiten,
 * damit winzige Differenzen nicht sofort blau↔rot werden.
 */
fun expandedAutoHeatmapRange(
    vMin: Int,
    vMax: Int,
    minSpan: Int = AUTO_HEATMAP_MIN_SPAN,
): Pair<Float, Float> {
    val mid = 0.5f * (vMin + vMax)
    val raw = (vMax - vMin).toFloat()
    val half = maxOf(0.5f * raw, 0.5f * minSpan)
    return (mid - half) to (mid + half)
}

/**
 * Bin-Wert → t∈[0,1] mit eigener Skala (wie Bridge `_v_to_t_scaled`).
 * Normbereich → grünlich; darunter bläulich, darüber rötlich.
 */
fun heatmapTScaled(v: Int, scale: HeatmapScale): Float {
    val tb = scale.thrBlue
    val nm = scale.normMin
    val nx = scale.normMax
    val tr = scale.thrRed
    if (v <= tb) return 0f
    if (v >= tr) return 1f
    if (v < nm) {
        if (nm <= tb) return 0.25f
        return 0.25f * (v - tb).toFloat() / (nm - tb).toFloat()
    }
    if (v > nx) {
        if (tr <= nx) return 0.85f
        return 0.75f + 0.25f * (v - nx).toFloat() / (tr - nx).toFloat()
    }
    if (nx <= nm) return 0.5f
    return 0.25f + 0.5f * (v - nm).toFloat() / (nx - nm).toFloat()
}

/** Heatmap t∈[0,1]: blau → grün → gelb → rot (wie Bridge). */
fun heatmapColor(t: Float): Color {
    val x = t.coerceIn(0f, 1f)
    val (r, g, b) = when {
        x < 0.25f -> {
            val u = x / 0.25f
            Triple(0, (100 + 155 * u).toInt(), 255)
        }
        x < 0.5f -> {
            val u = (x - 0.25f) / 0.25f
            Triple(0, 255, (255 - 255 * u).toInt())
        }
        x < 0.75f -> {
            val u = (x - 0.5f) / 0.25f
            Triple((255 * u).toInt(), 255, 0)
        }
        else -> {
            val u = (x - 0.75f) / 0.25f
            Triple(255, (255 - 255 * u).toInt(), 0)
        }
    }
    return Color(
        red = r.coerceIn(0, 255) / 255f,
        green = g.coerceIn(0, 255) / 255f,
        blue = b.coerceIn(0, 255) / 255f,
        alpha = 0.85f,
    )
}

/** Farbe für fehlende ACC-Bins (keine Messwerte) — wie Bridge-Normgrün. */
val StromBinNoDataColor = Color(0f, 180f / 255f, 120f / 255f, 0.85f)

/** Farbe für ACC-Bin: eigene Skala oder Auto mit Mindestspanne. */
fun stromBinHeatmapColor(
    value: Int,
    scale: HeatmapScale?,
    autoVMin: Int,
    autoVMax: Int,
): Color {
    // Fehlende Segmente (kein Messwert) → grün, damit der Ring geschlossen wirkt
    if (value <= 0) return StromBinNoDataColor
    if (scale != null && scale.isValid()) {
        return heatmapColor(heatmapTScaled(value, scale))
    }
    if (autoVMax <= autoVMin) {
        return StromBinNoDataColor
    }
    val (lo, hi) = expandedAutoHeatmapRange(autoVMin, autoVMax)
    val span = hi - lo
    if (span <= 0f) return StromBinNoDataColor
    val t = ((value - lo) / span).coerceIn(0f, 1f)
    return heatmapColor(t)
}
