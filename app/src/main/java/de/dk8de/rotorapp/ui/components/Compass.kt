package de.dk8de.rotorapp.ui.components

import android.content.Context
import android.content.res.Configuration
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import de.dk8de.rotorapp.R
import de.dk8de.rotorapp.ui.theme.BridgeBg
import de.dk8de.rotorapp.ui.theme.BridgeIst
import de.dk8de.rotorapp.ui.theme.BridgeRing
import de.dk8de.rotorapp.ui.theme.BridgeSoll
import de.dk8de.rotorapp.ui.theme.BridgeStop
import de.dk8de.rotorapp.ui.theme.BridgeTick
import de.dk8de.rotorapp.ui.theme.heatmapColor
import de.dk8de.rotorapp.ui.theme.HeatmapScale
import de.dk8de.rotorapp.ui.theme.stromBinHeatmapColor
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** Prozessweiter Cache — Windrose nicht bei jedem Pager-Wechsel neu dekodieren. */
private object WindroseCache {
    @Volatile
    private var cached: ImageBitmap? = null

    fun get(context: Context): ImageBitmap? {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val bmp = BitmapFactory.decodeResource(context.resources, R.drawable.windrose)
                ?.asImageBitmap()
            cached = bmp
            return bmp
        }
    }
}

/**
 * Azimuth-Kompass: Ist/Soll nur zeichnen.
 * Die Ist-Glättung läuft im Repository (wie Bridge AxisState + 33 ms Tick).
 */
@Composable
fun AzimuthCompass(
    currentDeg: Double?,
    targetDeg: Double?,
    onPick: (Double) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** Endanschlag / Versatz der gewählten Antenne (0 = Nord). */
    endstopDeg: Float = 0f,
    /** Öffnungswinkel-Overlay (0 = aus). Zentriert auf currentDeg. */
    beamOpeningDeg: Float = 0f,
    beamColor: Color = Color.Transparent,
    showBeamOverlay: Boolean = true,
    /** Bei Dipol: zweites Overlay 180° gegenüber. */
    beamDipole: Boolean = false,
    /** Strom-Heatmap 36 Bins (Rotor-Koordinaten + offset). */
    stromBins36: List<Int>? = null,
    showStromRing: Boolean = false,
    /** null = Auto mit Mindestspanne; sonst Bridge-Custom-Skala. */
    stromHeatmapScale: HeatmapScale? = null,
    /** Standzeit-Sektoren (Sekunden), Anzahl = List.size. */
    dwellSeconds: List<Float>? = null,
    showDwellRing: Boolean = false,
    dwellFullSeconds: Float = 300f,
    /** Windrichtung ° (Firmware: woher), null = kein Pfeil. */
    windDirDeg: Float? = null,
    /** `"from"` = woher, `"to"` = wohin (Pfeil um 180° drehen). */
    windDirMode: String = "from",
    /** HUD oben links: Titel + Wert (z. B. Außen / 24,9°C). */
    hudTopLeft: Pair<String, String>? = null,
    /** HUD oben rechts: eine oder mehrere Zeilen (Motor …). */
    hudTopRight: List<Pair<String, String>> = emptyList(),
    /** HUD unten links: Titel + Wert (Wind / km/h), Wert-Fußlinie = Kreisunten. */
    hudBottomLeft: Pair<String, String>? = null,
) {
    val context = LocalContext.current
    val windrose = remember(context) { WindroseCache.get(context) }
    val labelN = stringResource(R.string.compass_n)
    val labelS = stringResource(R.string.compass_s)
    val labelE = stringResource(R.string.compass_e)
    val labelW = stringResource(R.string.compass_w)
    val portrait =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    // Hochkant: Titel 2/3 größer; Werte etwas weniger (ragen sonst in den Kreis)
    val hudTitleScale = if (portrait) 5f / 3f else 1f
    val hudValueScale = if (portrait) 1.45f else 1f

    Canvas(
        modifier = modifier
            .aspectRatio(1f)
            .clipToBounds()
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val dx = offset.x - cx
                    val dy = offset.y - cy
                    if (hypot(dx.toDouble(), dy.toDouble()) < min(size.width, size.height) * 0.12) {
                        return@detectTapGestures
                    }
                    var deg = Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble()))
                    if (deg < 0) deg += 360.0
                    onPick(deg)
                }
            },
    ) {
        // Wie Bridge: Farbringe 7px + 1px Spalt außerhalb des weißen Kreises
        val ringW = 7.dp.toPx()
        val gapW = 1.dp.toPx()
        // Ring auch ohne Messwerte: fehlende Segmente → Grün
        val showStrom = showStromRing
        val showDwell = showDwellRing && !dwellSeconds.isNullOrEmpty()
        var outerSlots = 0
        if (showStrom) outerSlots++
        if (showDwell) outerSlots++
        val ringsBudget = if (outerSlots > 0) {
            outerSlots * ringW + (outerSlots - 1).coerceAtLeast(0) * gapW
        } else {
            0f
        }
        // Volle Breite: nur kleiner Rand + Farbringe, kein extra HUD-Platz
        val r = min(size.width, size.height) / 2f - 6.dp.toPx() - ringsBudget
        val c = Offset(size.width / 2f, size.height / 2f)
        val istWidth = 4.7.dp.toPx()
        val sollWidth = 4.dp.toPx()
        val offset = endstopDeg

        drawCircle(color = BridgeBg, radius = r, center = c)

        windrose?.let { rose ->
            val roseSize = (r * 1.55f).toInt().coerceAtLeast(1)
            val roseLeft = (c.x - roseSize / 2f).toInt()
            val roseTop = (c.y - roseSize / 2f).toInt()
            drawImage(
                image = rose,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(rose.width, rose.height),
                dstOffset = IntOffset(roseLeft, roseTop),
                dstSize = IntSize(roseSize, roseSize),
                alpha = 0.85f,
                blendMode = BlendMode.Screen,
            )
        }

        // Öffnungswinkel als zartes Flächen-Overlay (unter Ticks/Nadeln)
        if (showBeamOverlay && beamOpeningDeg > 0.5f && beamColor.alpha > 0f) {
            val center = (currentDeg ?: targetDeg)?.toFloat()
            if (center != null) {
                fun drawBeam(centerDeg: Float) {
                    val opening = beamOpeningDeg.coerceIn(0.5f, 360f)
                    val start = centerDeg - opening / 2f - 90f
                    drawArc(
                        color = beamColor,
                        startAngle = start,
                        sweepAngle = opening,
                        useCenter = true,
                        topLeft = Offset(c.x - r, c.y - r),
                        size = Size(r * 2f, r * 2f),
                    )
                }
                drawBeam(wrap360(center))
                if (beamDipole) {
                    drawBeam(wrap360(center + 180f))
                }
            }
        }

        drawCircle(
            color = BridgeRing,
            radius = r,
            center = c,
            style = Stroke(width = 2.dp.toPx()),
        )

        for (i in 0 until 360 step 5) {
            val rad = Math.toRadians(i.toDouble())
            val major = i % 10 == 0
            val outer = r
            val inner = if (major) r - 14.dp.toPx() else r - 8.dp.toPx()
            drawLine(
                color = BridgeTick,
                start = Offset(c.x + sin(rad).toFloat() * outer, c.y - cos(rad).toFloat() * outer),
                end = Offset(c.x + sin(rad).toFloat() * inner, c.y - cos(rad).toFloat() * inner),
                strokeWidth = if (major) 2.dp.toPx() else 1.dp.toPx(),
            )
        }

        // Roter Endanschlag am Antennenversatz (Spitze nach innen)
        run {
            val deg = wrap360(endstopDeg)
            val rad = Math.toRadians(deg.toDouble())
            val spread = Math.toRadians(4.0)
            val tipR = r * 0.96f
            val baseR = r
            val tip = Offset(
                c.x + sin(rad).toFloat() * tipR,
                c.y - cos(rad).toFloat() * tipR,
            )
            val baseL = Offset(
                c.x + sin(rad - spread).toFloat() * baseR,
                c.y - cos(rad - spread).toFloat() * baseR,
            )
            val baseRpt = Offset(
                c.x + sin(rad + spread).toFloat() * baseR,
                c.y - cos(rad + spread).toFloat() * baseR,
            )
            val tri = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(baseL.x, baseL.y)
                lineTo(baseRpt.x, baseRpt.y)
                close()
            }
            drawPath(tri, BridgeStop)
        }

        val degreePaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textAlign = android.graphics.Paint.Align.CENTER
            // Skaliert mit Radius — in Landscape nicht zu eng/groß
            textSize = (r * 0.055f).coerceIn(9.dp.toPx(), 12.dp.toPx())
            isAntiAlias = true
        }
        val cardinalPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#5EB5F7")
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = (r * 0.11f).coerceIn(14.dp.toPx(), 24.dp.toPx())
            isFakeBoldText = true
            isAntiAlias = true
        }

        // Alle 30° statt 20° — lesbarer, weniger Überlappung
        for (i in 30 until 360 step 30) {
            if (i % 90 == 0) continue
            val rad = Math.toRadians(i.toDouble())
            val lr = r - (degreePaint.textSize * 2.1f)
            val x = c.x + sin(rad).toFloat() * lr
            val y = c.y - cos(rad).toFloat() * lr + degreePaint.textSize / 3f
            drawContext.canvas.nativeCanvas.drawText("$i°", x, y, degreePaint)
        }

        val cardR = r - (cardinalPaint.textSize * 1.35f)
        drawContext.canvas.nativeCanvas.drawText(labelN, c.x, c.y - cardR + cardinalPaint.textSize / 3f, cardinalPaint)
        drawContext.canvas.nativeCanvas.drawText(labelS, c.x, c.y + cardR + cardinalPaint.textSize / 3f, cardinalPaint)
        drawContext.canvas.nativeCanvas.drawText(labelE, c.x + cardR, c.y + cardinalPaint.textSize / 3f, cardinalPaint)
        drawContext.canvas.nativeCanvas.drawText(labelW, c.x - cardR, c.y + cardinalPaint.textSize / 3f, cardinalPaint)

        fun needle(deg: Float, color: Color, length: Float, width: Float) {
            val rad = Math.toRadians(wrap360(deg).toDouble())
            val tipLen = 14.dp.toPx()
            val baseHalf = 5.5.dp.toPx()
            // Spitze etwas weiter außen; Schaft endet an der Spitzenbasis (kein Überstand)
            val tip = Offset(
                c.x + sin(rad).toFloat() * length,
                c.y - cos(rad).toFloat() * length,
            )
            val shaftEnd = Offset(
                tip.x - sin(rad).toFloat() * tipLen,
                tip.y + cos(rad).toFloat() * tipLen,
            )
            drawLine(color, c, shaftEnd, strokeWidth = width, cap = StrokeCap.Butt)
            val head = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(
                    shaftEnd.x - cos(rad).toFloat() * baseHalf,
                    shaftEnd.y - sin(rad).toFloat() * baseHalf,
                )
                lineTo(
                    shaftEnd.x + cos(rad).toFloat() * baseHalf,
                    shaftEnd.y + sin(rad).toFloat() * baseHalf,
                )
                close()
            }
            drawPath(head, color)
        }

        targetDeg?.let { needle(it.toFloat(), BridgeSoll, r * 0.74f, sollWidth) }
        currentDeg?.let { needle(it.toFloat(), BridgeIst, r * 0.84f, istWidth) }

        // Wind: von der Mitte aus, blau, mit Spitze (wie Bridge ~0.46·r)
        windDirDeg?.let { raw ->
            val windColor = Color(0xFF5AB0FF)
            val wdeg = if (windDirMode.equals("to", ignoreCase = true)) {
                wrap360(raw + 180f)
            } else {
                wrap360(raw)
            }
            val rad = Math.toRadians(wdeg.toDouble())
            val tipLen = 13.dp.toPx()
            val baseHalf = 5.dp.toPx()
            val length = r * 0.48f
            val tip = Offset(
                c.x + sin(rad).toFloat() * length,
                c.y - cos(rad).toFloat() * length,
            )
            val shaftEnd = Offset(
                tip.x - sin(rad).toFloat() * tipLen,
                tip.y + cos(rad).toFloat() * tipLen,
            )
            drawLine(windColor, c, shaftEnd, strokeWidth = 3.2.dp.toPx(), cap = StrokeCap.Butt)
            val head = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(
                    shaftEnd.x - cos(rad).toFloat() * baseHalf,
                    shaftEnd.y - sin(rad).toFloat() * baseHalf,
                )
                lineTo(
                    shaftEnd.x + cos(rad).toFloat() * baseHalf,
                    shaftEnd.y + sin(rad).toFloat() * baseHalf,
                )
                close()
            }
            drawPath(head, windColor)
        }

        drawCircle(BridgeRing, radius = 5.5.dp.toPx(), center = c)

        // Heatmap-Ringe AUSSERHALB des weißen Kreises: innen→außen = Strom → Standzeit
        fun drawOuterHeatRing(
            baseInner: Float,
            n: Int,
            colorAt: (Int) -> Color?,
        ) {
            if (n <= 0) return
            val step = 360f / n
            val arcR = baseInner + ringW / 2f
            for (i in 0 until n) {
                val color = colorAt(i) ?: continue
                if (color.alpha <= 0f) continue
                val rotorStart = i * step
                val dispStart = wrap360(rotorStart + offset)
                drawArc(
                    color = color,
                    startAngle = dispStart - 90f,
                    sweepAngle = step,
                    useCenter = false,
                    topLeft = Offset(c.x - arcR, c.y - arcR),
                    size = Size(arcR * 2f, arcR * 2f),
                    style = Stroke(width = ringW, cap = StrokeCap.Butt),
                )
            }
        }

        var ringBase = r
        if (showStrom) {
            val vals = stromBins36.orEmpty()
            val usable = vals.filter { it > 0 }
            val vMin = usable.minOrNull() ?: 0
            val vMax = usable.maxOrNull() ?: vMin
            val nStrom = 36
            drawOuterHeatRing(ringBase, nStrom) { i ->
                val v = vals.getOrElse(i) { 0 }
                stromBinHeatmapColor(v, stromHeatmapScale, vMin, vMax)
            }
            ringBase += ringW
            if (showDwell) {
                drawCircle(
                    color = Color.Black,
                    radius = ringBase + gapW / 2f,
                    center = c,
                    style = Stroke(width = gapW, cap = StrokeCap.Butt),
                )
                ringBase += gapW
            }
        }
        if (showDwell) {
            val secs = dwellSeconds!!
            val nDwell = secs.size.coerceAtLeast(1)
            val fs = dwellFullSeconds.coerceAtLeast(1f)
            drawOuterHeatRing(ringBase, nDwell) { i ->
                val sec = secs.getOrElse(i) { 0f }
                // Alle Segmente von Anfang an sichtbar: 0 s = blau
                heatmapColor((sec / fs).coerceIn(0f, 1f))
            }
        }

        // Temp / Wind in den Ecken (Kompass bleibt volle Breite)
        val outerR = r + ringsBudget
        val topEdge = c.y - outerR
        val bottomEdge = c.y + outerR
        val edgePad = 4.dp.toPx()
        // Skaliert mit Kompassradius (Tablets groß), nur untere Grenze für Phones
        val titleSize = (r * 0.062f * hudTitleScale).coerceAtLeast(11.dp.toPx())
        val valueSize = (r * 0.078f * hudValueScale).coerceAtLeast(13.dp.toPx())
        val lineGap = (2.dp.toPx() * hudTitleScale).coerceAtLeast(2.dp.toPx())
        val titlePaint = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#9A9A9A")
            textSize = titleSize
            isAntiAlias = true
            isFakeBoldText = true
        }
        val valuePaint = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#E8E8E8")
            textSize = valueSize
            isAntiAlias = true
            isFakeBoldText = true
        }
        val windValuePaint = android.graphics.Paint(valuePaint).apply {
            color = android.graphics.Color.parseColor("#5EB5F7")
        }
        val nc = drawContext.canvas.nativeCanvas

        // Oben links / rechts: an Canvas-Rand = bündig mit Kreiskante (volle Breite)
        hudTopLeft?.let { (title, value) ->
            titlePaint.textAlign = android.graphics.Paint.Align.LEFT
            valuePaint.textAlign = android.graphics.Paint.Align.LEFT
            val x = edgePad
            val titleBase = topEdge - titlePaint.ascent()
            nc.drawText(title, x, titleBase, titlePaint)
            val valueBase = titleBase + titlePaint.descent() + lineGap - valuePaint.ascent()
            nc.drawText(value, x, valueBase, valuePaint)
        }
        if (hudTopRight.isNotEmpty()) {
            titlePaint.textAlign = android.graphics.Paint.Align.RIGHT
            valuePaint.textAlign = android.graphics.Paint.Align.RIGHT
            val x = size.width - edgePad
            var titleBase = topEdge - titlePaint.ascent()
            for ((title, value) in hudTopRight) {
                nc.drawText(title, x, titleBase, titlePaint)
                val valueBase = titleBase + titlePaint.descent() + lineGap - valuePaint.ascent()
                nc.drawText(value, x, valueBase, valuePaint)
                titleBase = valueBase + valuePaint.descent() + lineGap - titlePaint.ascent()
            }
        }
        hudBottomLeft?.let { (title, value) ->
            titlePaint.textAlign = android.graphics.Paint.Align.LEFT
            windValuePaint.textAlign = android.graphics.Paint.Align.LEFT
            val x = edgePad
            val valueBase = bottomEdge
            val titleBase = valueBase + windValuePaint.ascent() - lineGap - titlePaint.descent()
            nc.drawText(title, x, titleBase, titlePaint)
            nc.drawText(value, x, valueBase, windValuePaint)
        }
    }
}

/** Sanfte Overlay-Farben je Antennen-Slot (1–3). */
fun antennaBeamColor(slot: Int): Color = when (slot.coerceIn(1, 3)) {
    1 -> Color(0xFF5EB5F7).copy(alpha = 0.18f) // sanftes Blau
    2 -> Color(0xFF7BC67E).copy(alpha = 0.18f) // sanftes Grün
    else -> Color(0xFFC9A0DC).copy(alpha = 0.18f) // sanftes Flieder
}

/**
 * Elevation: 90° = Viertelkreis (0 links, 90 oben), 180° = Halbkreis (0 links, 90 oben, 180 rechts).
 */
@Composable
fun ElevationCompass(
    currentDeg: Double?,
    targetDeg: Double?,
    onPick: (Double) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    maxDeg: Double = 90.0,
    /** Strom-Heatmap 36 Firmware-Bins, auf 90°/180° verteilt. */
    stromBins36: List<Int>? = null,
    showStromRing: Boolean = false,
    stromHeatmapScale: HeatmapScale? = null,
    /** HUD oben rechts (z. B. Motor EL). */
    hudTopRight: Pair<String, String>? = null,
) {
    val is180 = maxDeg >= 179.5
    val aspect = if (is180) 1.85f else 1.15f
    val portrait =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    // Gleiche Skalen wie AZ-Kompass (Hochkant größer)
    val hudTitleScale = if (portrait) 5f / 3f else 1f
    val hudValueScale = if (portrait) 1.45f else 1f
    val showStrom = showStromRing

    Canvas(
        modifier = modifier
            .aspectRatio(aspect)
            .clipToBounds()
            .pointerInput(enabled, is180) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    // 0 links / 90 oben / 180 rechts → Elev = 180 − atan2(up, right)
                    val cx: Float
                    val cy: Float
                    val maxPick: Double
                    if (is180) {
                        cx = size.width * 0.5f
                        cy = size.height * 0.88f
                        maxPick = 180.0
                    } else {
                        cx = size.width * 0.88f
                        cy = size.height * 0.88f
                        maxPick = 90.0
                    }
                    val dx = offset.x - cx
                    val dy = cy - offset.y
                    val mathDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
                    val elev = (180.0 - mathDeg).coerceIn(0.0, maxPick)
                    onPick(elev)
                }
            },
    ) {
        val strokeW = 2.dp.toPx()
        val istWidth = 4.7.dp.toPx()
        val sollWidth = 4.dp.toPx()
        val ringW = 7.dp.toPx()
        val outerBudget = if (showStrom) ringW + 14.dp.toPx() else 0f

        /** Elevationswinkel → Zeichenrichtung: 0 links, 90 oben, 180 rechts. */
        fun elevRad(elevDeg: Float): Double =
            Math.toRadians((180f - elevDeg).toDouble())

        fun elevUnit(elevDeg: Float): Pair<Float, Float> {
            val rad = elevRad(elevDeg)
            return cos(rad).toFloat() to (-sin(rad).toFloat())
        }

        val origin: Offset
        val r: Float
        val sweepMax: Float

        if (is180) {
            val labelRoom = 20.dp.toPx() + outerBudget
            origin = Offset(size.width * 0.5f, size.height * 0.90f)
            r = minOf(
                size.width * 0.46f - outerBudget * 0.35f,
                size.height * 0.76f,
                origin.y - labelRoom - strokeW,
            )
            sweepMax = 180f
            val oval = androidx.compose.ui.geometry.Rect(
                origin.x - r,
                origin.y - r,
                origin.x + r,
                origin.y + r,
            )
            // Oberer Halbkreis: 0° links → 180° rechts über oben
            // Compose: 0=rechts, 90=unten, 180=links, -90=oben; positiv = Uhrzeigersinn
            val fillPath = Path().apply {
                moveTo(origin.x, origin.y)
                lineTo(origin.x - r, origin.y) // 0° links
                arcTo(oval, startAngleDegrees = 180f, sweepAngleDegrees = 180f, forceMoveTo = false)
                close()
            }
            drawPath(fillPath, BridgeBg)
            drawPath(
                Path().apply {
                    arcTo(oval, startAngleDegrees = 180f, sweepAngleDegrees = 180f, forceMoveTo = true)
                },
                BridgeRing,
                style = Stroke(width = strokeW, cap = StrokeCap.Round),
            )
            drawLine(
                BridgeRing,
                Offset(origin.x - r, origin.y),
                Offset(origin.x + r, origin.y),
                strokeWidth = strokeW,
                cap = StrokeCap.Round,
            )
        } else {
            // Viertelkreis: Ursprung unten rechts — 0 links, 90 oben
            val labelRoom = 22.dp.toPx() + outerBudget
            origin = Offset(size.width * 0.90f, size.height * 0.92f)
            r = minOf(
                origin.x - labelRoom,
                origin.y - labelRoom - strokeW,
            ).coerceAtLeast(24.dp.toPx())
            sweepMax = 90f
            // Von links (180°) im Uhrzeigersinn nach oben (270°/−90°)
            drawArc(
                color = BridgeBg,
                startAngle = 180f,
                sweepAngle = 90f,
                useCenter = true,
                topLeft = Offset(origin.x - r, origin.y - r),
                size = Size(r * 2, r * 2),
            )
            drawArc(
                color = BridgeRing,
                startAngle = 180f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(origin.x - r, origin.y - r),
                size = Size(r * 2, r * 2),
                style = Stroke(width = strokeW),
            )
            drawLine(
                BridgeRing,
                origin,
                Offset(origin.x - r, origin.y),
                strokeWidth = strokeW,
                cap = StrokeCap.Round,
            )
            drawLine(
                BridgeRing,
                origin,
                Offset(origin.x, origin.y - r),
                strokeWidth = strokeW,
                cap = StrokeCap.Round,
            )
        }

        val tickStep = if (is180) 10 else 5
        for (i in 0..sweepMax.toInt() step tickStep) {
            val (ux, uy) = elevUnit(i.toFloat())
            val major = i % 10 == 0
            val outer = r
            val inner = if (major) r - 12.dp.toPx() else r - 7.dp.toPx()
            drawLine(
                BridgeTick,
                Offset(origin.x + ux * outer, origin.y + uy * outer),
                Offset(origin.x + ux * inner, origin.y + uy * inner),
                strokeWidth = if (major) 2.dp.toPx() else 1.dp.toPx(),
            )
        }

        if (showStrom) {
            val vals = stromBins36.orEmpty()
            val skip = 5
            val used = if (vals.size >= 36) {
                vals.subList(skip, 36 - skip)
            } else if (vals.isNotEmpty()) {
                vals
            } else {
                List(26) { 0 }
            }
            val nUsed = used.size.coerceAtLeast(1)
            val step = sweepMax / nUsed
            val usable = used.filter { it > 0 }
            val vMin = usable.minOrNull() ?: 0
            val vMax = usable.maxOrNull() ?: vMin
            val arcR = r + ringW / 2f
            for (i in 0 until nUsed) {
                val v = used.getOrElse(i) { 0 }
                val color = stromBinHeatmapColor(v, stromHeatmapScale, vMin, vMax)
                if (color.alpha <= 0f) continue
                val a0 = i * step
                // Compose-Winkel: elev 0 → −180°, steigend im Uhrzeigersinn
                drawArc(
                    color = color,
                    startAngle = a0 - 180f,
                    sweepAngle = step,
                    useCenter = false,
                    topLeft = Offset(origin.x - arcR, origin.y - arcR),
                    size = Size(arcR * 2f, arcR * 2f),
                    style = Stroke(width = ringW, cap = StrokeCap.Butt),
                )
            }
        }

        fun needle(deg: Float, color: Color, length: Float, width: Float) {
            val (ux, uy) = elevUnit(deg.coerceIn(0f, sweepMax))
            val tipLen = 14.dp.toPx()
            val baseHalf = 5.5.dp.toPx()
            val tip = Offset(origin.x + ux * length, origin.y + uy * length)
            val shaftEnd = Offset(tip.x - ux * tipLen, tip.y - uy * tipLen)
            drawLine(color, origin, shaftEnd, strokeWidth = width, cap = StrokeCap.Butt)
            val px = -uy
            val py = ux
            val head = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(shaftEnd.x - px * baseHalf, shaftEnd.y - py * baseHalf)
                lineTo(shaftEnd.x + px * baseHalf, shaftEnd.y + py * baseHalf)
                close()
            }
            drawPath(head, color)
        }
        targetDeg?.let {
            needle(it.toFloat().coerceIn(0f, sweepMax), BridgeSoll, r * 0.84f, sollWidth)
        }
        currentDeg?.let {
            needle(it.toFloat().coerceIn(0f, sweepMax), BridgeIst, r * 0.92f, istWidth)
        }
        drawCircle(BridgeRing, radius = 5.5.dp.toPx(), center = origin)

        if (is180) {
            val oval = androidx.compose.ui.geometry.Rect(
                origin.x - r,
                origin.y - r,
                origin.x + r,
                origin.y + r,
            )
            drawPath(
                Path().apply {
                    arcTo(oval, startAngleDegrees = 180f, sweepAngleDegrees = 180f, forceMoveTo = true)
                },
                BridgeRing,
                style = Stroke(width = strokeW, cap = StrokeCap.Round),
            )
        }

        // Gradzahlen wie AZ
        val labelPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = (r * 0.055f).coerceIn(9.dp.toPx(), 12.dp.toPx())
            isAntiAlias = true
        }
        val fm = labelPaint.fontMetrics
        val labels = if (is180) {
            listOf(0, 30, 60, 90, 120, 150, 180)
        } else {
            (0..90 step 10).toList()
        }
        val labelGap = if (showStrom) ringW + labelPaint.textSize * 1.05f else labelPaint.textSize * 0.95f
        for (deg in labels) {
            val (ux, uy) = elevUnit(deg.toFloat())
            val lr = r + labelGap
            var cx = origin.x + ux * lr
            var cy = origin.y + uy * lr
            when (deg) {
                0 -> {
                    cx -= labelPaint.textSize * 0.25f
                    cy += labelPaint.textSize * 0.15f
                }
                90 -> cy -= labelPaint.textSize * 0.1f
                180 -> {
                    cx += labelPaint.textSize * 0.25f
                    cy += labelPaint.textSize * 0.15f
                }
            }
            val text = "${deg}°"
            val halfW = labelPaint.measureText(text) * 0.5f
            cx = cx.coerceIn(halfW + 2.dp.toPx(), size.width - halfW - 2.dp.toPx())
            val baseline = (cy - (fm.ascent + fm.descent) / 2f).coerceIn(
                -fm.ascent + 2.dp.toPx(),
                size.height - fm.descent - 2.dp.toPx(),
            )
            drawContext.canvas.nativeCanvas.drawText(text, cx, baseline, labelPaint)
        }

        // Motor EL — gleiche Größenformel wie AZ-HUD
        hudTopRight?.let { (title, value) ->
            val edgePad = 4.dp.toPx()
            val titleSize = (r * 0.062f * hudTitleScale).coerceAtLeast(11.dp.toPx())
            val valueSize = (r * 0.078f * hudValueScale).coerceAtLeast(13.dp.toPx())
            val lineGap = (2.dp.toPx() * hudTitleScale).coerceAtLeast(2.dp.toPx())
            val titlePaint = android.graphics.Paint().apply {
                color = android.graphics.Color.parseColor("#9A9A9A")
                textSize = titleSize
                isAntiAlias = true
                isFakeBoldText = true
                // 90°-Modus: Ursprung rechts → HUD oben links, sonst oben rechts
                textAlign = if (is180) {
                    android.graphics.Paint.Align.RIGHT
                } else {
                    android.graphics.Paint.Align.LEFT
                }
            }
            val valuePaint = android.graphics.Paint(titlePaint).apply {
                color = android.graphics.Color.parseColor("#E8E8E8")
                textSize = valueSize
            }
            val nc = drawContext.canvas.nativeCanvas
            val x = if (is180) size.width - edgePad else edgePad
            val titleBase = edgePad - titlePaint.ascent()
            nc.drawText(title, x, titleBase, titlePaint)
            val valueBase = titleBase + titlePaint.descent() + lineGap - valuePaint.ascent()
            nc.drawText(value, x, valueBase, valuePaint)
        }
    }
}

private fun wrap360(deg: Float): Float {
    var d = deg % 360f
    if (d < 0f) d += 360f
    return d
}
