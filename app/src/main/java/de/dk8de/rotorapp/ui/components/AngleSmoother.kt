package de.dk8de.rotorapp.ui.components

import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 1:1-Port von RotorTcpBridge `AxisState` Anzeige-Glättung:
 * - GETPOS-Sample → Segment-Lerp (ohne Overshoot)
 * - Idle/Fallback → Unity SmoothDamp
 *
 * Intern in Grad (Bridge arbeitet in 0,1°-Einheiten; Faktoren entsprechend skaliert).
 */
class AngleSmoother(
    private val wrap360: Boolean,
) {
    companion object {
        private const val SMOOTH_TIME_DYNAMIC_S = 0.20f
        private const val SMOOTH_TIME_IDLE_S = 0.42f
        // Bridge: 3200 / 900 in 0,1°/s → 320 / 90 °/s
        private const val MAX_SPEED_DYNAMIC_DPS = 320f
        private const val MAX_SPEED_IDLE_DPS = 90f
        // Bridge: 500 in 0,1° → 50°
        private const val SNAP_DEG = 50f
        private const val INTERP_DUR_MIN_S = 0.12f
        private const val INTERP_DUR_MAX_S = 0.55f
        private const val INTERP_SAMPLE_DT_MIN_S = 0.02f
        private const val INTERP_SAMPLE_DT_MAX_S = 1.5f
    }

    private var display = 0f
    private var velocity = 0f
    private var lastTickMs = 0L
    private var lastSampleMs = 0L
    private var lastRaw = 0f
    private var hasRaw = false

    private var interpFrom = 0f
    private var interpTo = 0f
    private var interpStartMs = 0L
    private var interpDurMs = 0L

    private var hasDisplay = false
    @Volatile
    var visible: Boolean = false
        private set

    private var dynamic = false
    private var holdTarget = 0f

    @Synchronized
    fun setDynamic(moving: Boolean) {
        dynamic = moving
    }

    @Synchronized
    fun clear() {
        visible = false
        hasDisplay = false
        hasRaw = false
        velocity = 0f
        interpDurMs = 0L
        interpStartMs = 0L
        lastTickMs = 0L
        lastSampleMs = 0L
    }

    /**
     * Neues GETPOS-Sample (wie `AxisState.update_position_sample`).
     * @param expectedPeriodS erwartetes Poll-Intervall (Bridge: pos_fast ≈ 0.2…0.25)
     */
    @Synchronized
    fun updateSample(
        deg: Float?,
        nowMs: Long = SystemClock.elapsedRealtime(),
        expectedPeriodS: Float = 0.25f,
    ) {
        if (deg == null) {
            clear()
            return
        }
        val tgt = if (wrap360) wrap(deg) else deg
        holdTarget = tgt

        if (!hasDisplay || !visible) {
            display = tgt
            lastRaw = tgt
            hasRaw = true
            velocity = 0f
            hasDisplay = true
            visible = true
            lastSampleMs = nowMs
            clearInterp()
            return
        }

        val prevRaw = if (hasRaw) lastRaw else display
        val jump = abs(delta(prevRaw, tgt))
        val prevSample = lastSampleMs
        lastSampleMs = nowMs
        lastRaw = tgt
        hasRaw = true

        // Erste Anzeige oder großer Sprung: hart setzen
        if (jump >= SNAP_DEG || lastTickMs <= 0L) {
            display = tgt
            velocity = 0f
            clearInterp()
            if (jump >= SNAP_DEG) lastTickMs = 0L
            return
        }

        var dur = expectedPeriodS
        if (prevSample > 0L) {
            val dtS = (nowMs - prevSample) / 1000f
            if (dtS in INTERP_SAMPLE_DT_MIN_S..INTERP_SAMPLE_DT_MAX_S) {
                dur = dtS
            }
        }
        dur = dur.coerceIn(INTERP_DUR_MIN_S, INTERP_DUR_MAX_S)

        // Start = aktuelle Anzeige; Ende = neues Sample (nie darüber hinaus)
        interpFrom = display
        interpTo = tgt
        interpStartMs = nowMs
        interpDurMs = (dur * 1000f).toLong().coerceAtLeast(1L)
        velocity = 0f
        visible = true
        hasDisplay = true
    }

    /** Aktueller Anzeigewert ohne Zeitfortschritt (nach Sample sofort gültig). */
    @Synchronized
    fun current(): Float? = if (visible && hasDisplay) display else null

    /** Wie `AxisState.get_smoothed_pos_d10f` — einmal pro Render-Tick (~33 ms). */
    @Synchronized
    fun tick(nowMs: Long = SystemClock.elapsedRealtime()): Float? {
        if (!visible || !hasDisplay) return null

        // Aktives Segment (auch nach u≥1 beibehalten, bis neues Sample kommt — wie Bridge)
        if (interpDurMs > 0L && interpStartMs > 0L) {
            val u = (nowMs - interpStartMs).toFloat() / interpDurMs.toFloat()
            display = when {
                u <= 0f -> interpFrom
                u >= 1f -> interpTo
                else -> interpFrom + delta(interpFrom, interpTo) * u
            }
            if (wrap360) display = wrap(display)
            lastTickMs = nowMs
            velocity = 0f
            return display
        }

        if (lastTickMs <= 0L) {
            lastTickMs = nowMs
            display = holdTarget
            return display
        }

        var dt = (nowMs - lastTickMs) / 1000f
        if (dt < 0f) {
            lastTickMs = nowMs
            return display
        }
        dt = min(dt, 0.12f)
        lastTickMs = nowMs

        val err = delta(display, holdTarget)
        if (abs(err) >= SNAP_DEG) {
            display = holdTarget
            velocity = 0f
        } else {
            val targetLin = display + err
            val st = if (dynamic) SMOOTH_TIME_DYNAMIC_S else SMOOTH_TIME_IDLE_S
            val mx = if (dynamic) MAX_SPEED_DYNAMIC_DPS else MAX_SPEED_IDLE_DPS
            val (x, v) = smoothDamp(display, targetLin, velocity, st, mx, dt)
            display = if (wrap360) wrap(x) else x
            velocity = v
        }
        return display
    }

    private fun clearInterp() {
        interpDurMs = 0L
        interpStartMs = 0L
        interpFrom = display
        interpTo = display
    }

    private fun delta(from: Float, to: Float): Float =
        if (wrap360) shortestDelta(from, to) else (to - from)

    private fun wrap(deg: Float): Float {
        var d = deg % 360f
        if (d < 0f) d += 360f
        return d
    }

    private fun shortestDelta(from: Float, to: Float): Float {
        val a = wrap(from)
        val b = wrap(to)
        return ((b - a + 540f) % 360f) - 180f
    }

    private fun smoothDamp(
        current: Float,
        target: Float,
        currentVelocity: Float,
        smoothTime: Float,
        maxSpeed: Float,
        deltaTime: Float,
    ): Pair<Float, Float> {
        val st = max(0.0001f, smoothTime)
        val omega = 2f / st
        val x = omega * deltaTime
        val exp = 1f / (1f + x + 0.48f * x * x + 0.235f * x * x * x)
        var change = current - target
        val originalTo = target
        val maxChange = maxSpeed * st
        change = change.coerceIn(-maxChange, maxChange)
        val targetAdj = current - change
        val temp = (currentVelocity + omega * change) * deltaTime
        var newVel = (currentVelocity - omega * temp) * exp
        var output = targetAdj + (change + temp) * exp
        if ((originalTo - current > 0f) == (output > originalTo)) {
            output = originalTo
            newVel = if (abs(deltaTime) > 1e-12f) (output - originalTo) / deltaTime else 0f
        }
        return output to newVel
    }
}
