package de.dk8de.rotorapp.ui

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import de.dk8de.rotorapp.R
import de.dk8de.rotorapp.geo.GeoUtils
import de.dk8de.rotorapp.rotor.AntennaMath
import de.dk8de.rotorapp.rotor.AntennaSlot
import de.dk8de.rotorapp.ui.theme.BridgeMuted
import de.dk8de.rotorapp.ui.theme.BridgePanel
import de.dk8de.rotorapp.ui.theme.BridgeText
import org.json.JSONArray
import org.json.JSONObject

/** Karten-Seite (Pager): Leaflet bleibt gecacht; [active] steuert Sichtbarkeit. */
@Composable
fun MapScreen(
    state: AppUiState,
    onMapClickBearing: (displayBearingDeg: Double) -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageReady by remember { mutableStateOf(false) }
    var lastStatus by remember { mutableStateOf("") }
    var locatorOverlay by remember { mutableStateOf(true) }
    val latestState = rememberUpdatedState(state)
    val latestOnClick = rememberUpdatedState(onMapClickBearing)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val mapBarHint = stringResource(R.string.map_bar_hint)
    val mapLocatorOn = stringResource(R.string.map_locator_on)
    val mapLocatorOff = stringResource(R.string.map_locator_off)

    fun pushLocatorOverlay(wv: WebView, show: Boolean) {
        wv.evaluateJavascript(
            "try{if(window.setMapLocatorOverlay){window.setMapLocatorOverlay(${show}, false);}}catch(e){}",
            null,
        )
    }

    val beamJson = remember(
        state.rotor.azSmoothDeg,
        state.rotor.azDeg,
        state.rotor.azTarget,
        state.rotor.azCompassTarget,
        state.rotor.selectedAntenna,
        state.rotor.antennas,
        state.displayPrefs.locationLat,
        state.displayPrefs.locationLon,
        state.displayPrefs.locationLocator,
        lastStatus,
    ) {
        buildMapBeamJson(state, statusLine = lastStatus.ifBlank { null }, recenter = false)
    }

    fun pushBeam(wv: WebView, json: String, invalidate: Boolean) {
        val escaped = JSONObject.quote(json)
        val inv = if (invalidate) {
            ";try{if(window.map){window.map.invalidateSize(true);}}catch(e){}"
        } else {
            ""
        }
        wv.evaluateJavascript(
            "try{window.updateBeam(JSON.parse($escaped));}catch(e){}$inv",
            null,
        )
    }

    LaunchedEffect(beamJson, pageReady, webView, active) {
        if (!pageReady || !active) return@LaunchedEffect
        val wv = webView ?: return@LaunchedEffect
        pushBeam(wv, beamJson, invalidate = true)
    }

    LaunchedEffect(locatorOverlay, pageReady, webView, active) {
        if (!pageReady || !active) return@LaunchedEffect
        val wv = webView ?: return@LaunchedEffect
        pushLocatorOverlay(wv, locatorOverlay)
    }

    LaunchedEffect(active, pageReady, webView) {
        val wv = webView ?: return@LaunchedEffect
        wv.visibility = if (active) android.view.View.VISIBLE else android.view.View.INVISIBLE
        if (active && pageReady) {
            wv.post {
                wv.evaluateJavascript(
                    "try{if(window.map){window.map.invalidateSize(true);}}catch(e){}",
                    null,
                )
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1C1C1C)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BridgePanel)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = mapBarHint,
                color = BridgeMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 4.dp),
            )
            TextButton(onClick = { locatorOverlay = !locatorOverlay }) {
                Text(
                    text = if (locatorOverlay) mapLocatorOn else mapLocatorOff,
                    color = if (locatorOverlay) BridgeText else Color(0xFF9E9E9E),
                    fontSize = 13.sp,
                )
            }
        }

        Box(modifier = Modifier
            .weight(1f)
            .fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    @SuppressLint("SetJavaScriptEnabled")
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        setBackgroundColor(AndroidColor.WHITE)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.allowFileAccess = true
                        settings.allowContentAccess = true
                        @Suppress("DEPRECATION")
                        settings.allowFileAccessFromFileURLs = true
                        @Suppress("DEPRECATION")
                        settings.allowUniversalAccessFromFileURLs = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                pageReady = true
                                val wv = view ?: return
                                val init = buildMapBeamJson(
                                    latestState.value,
                                    statusLine = null,
                                    recenter = true,
                                )
                                pushBeam(wv, init, invalidate = true)
                                wv.postDelayed({
                                    wv.evaluateJavascript(
                                        "try{if(window.map){window.map.invalidateSize(true);}}catch(e){}",
                                        null,
                                    )
                                }, 150)
                                wv.postDelayed({
                                    wv.evaluateJavascript(
                                        "try{if(window.map){window.map.invalidateSize(true);}}catch(e){}",
                                        null,
                                    )
                                }, 500)
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?,
                            ) {
                                if (request?.isForMainFrame == true) {
                                    val errMsg = ctx.getString(R.string.map_load_error)
                                    view?.loadData(
                                        "<html><body style='background:#222;color:#fff;font-family:sans-serif;padding:16px'>" +
                                            "<p>$errMsg</p>" +
                                            "<p>${error?.description ?: "?"}</p></body></html>",
                                        "text/html",
                                        "utf-8",
                                    )
                                }
                            }
                        }
                        addJavascriptInterface(
                            object {
                                @JavascriptInterface
                                fun onMapClick(lat: Double, lon: Double) {
                                    val s = latestState.value
                                    val (slat, slon) = s.displayPrefs.effectiveLatLon()
                                    val bearing = GeoUtils.bearingDeg(slat, slon, lat, lon)
                                    val dist = GeoUtils.haversineKm(slat, slon, lat, lon)
                                    mainHandler.post {
                                        lastStatus = ctx.getString(
                                            R.string.map_click_status,
                                            bearing,
                                            dist,
                                        )
                                        latestOnClick.value(bearing)
                                    }
                                }
                            },
                            "RotorMap",
                        )
                        val html = ctx.assets.open("map/map.html").bufferedReader().use { it.readText() }
                        loadDataWithBaseURL(
                            "file:///android_asset/map/",
                            html,
                            "text/html",
                            "utf-8",
                            null,
                        )
                        webView = this
                    }
                },
                update = { view ->
                    view.visibility =
                        if (active) android.view.View.VISIBLE else android.view.View.INVISIBLE
                    if (pageReady && active) {
                        view.post {
                            view.evaluateJavascript(
                                "try{if(window.map){window.map.invalidateSize(true);}}catch(e){}",
                                null,
                            )
                        }
                    }
                },
            )

            if (active) {
                MapWindOverlay(
                    state = state,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp),
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                destroy()
            }
            webView = null
        }
    }
}

/** Windrichtung und -geschwindigkeit oben rechts auf der Karte (ohne Temperaturen). */
@Composable
private fun MapWindOverlay(
    state: AppUiState,
    modifier: Modifier = Modifier,
) {
    val connected = state.rotor.connected
    val windOn = state.activeProfile?.enableWind == true
    val showWind = windOn && (
        state.rotor.windHwEnabled == true ||
            state.rotor.windKmh != null ||
            state.rotor.windDirDeg != null
        )
    if (!showWind) return

    val windDirMode = state.windDirMode
    val rawDir = state.rotor.windDirDeg
    val drawDir = rawDir?.let { d ->
        val w = AntennaMath.wrap360(d)
        if (windDirMode.equals("to", ignoreCase = true)) AntennaMath.wrap360(w + 180.0) else w
    }
    val windTxt = when {
        !connected -> "–.– km/h"
        state.rotor.windKmh != null -> "%.1f km/h".format(state.rotor.windKmh)
        else -> "–.– km/h"
    }

    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = modifier
            .widthIn(min = 96.dp)
            .background(Color(0xFF1C1C1E).copy(alpha = 0.72f), shape)
            .border(1.dp, Color(0xFFB4B4BE).copy(alpha = 0.25f), shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Canvas(modifier = Modifier.size(52.dp)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = size.minDimension * 0.38f
            drawCircle(
                color = Color(0x66FFFFFF),
                radius = r * 1.15f,
                center = Offset(cx, cy),
                style = Stroke(width = 1.2f),
            )
            drawDir?.let { deg ->
                rotate(degrees = deg.toFloat(), pivot = Offset(cx, cy)) {
                    val shaft = Path().apply {
                        moveTo(cx, cy + r * 0.75f)
                        lineTo(cx, cy - r * 0.55f)
                    }
                    drawPath(
                        path = shaft,
                        color = Color(0xFF5EB5F7),
                        style = Stroke(width = 3.5f, cap = StrokeCap.Round),
                    )
                    val tip = Path().apply {
                        moveTo(cx, cy - r * 0.95f)
                        lineTo(cx - r * 0.28f, cy - r * 0.35f)
                        lineTo(cx + r * 0.28f, cy - r * 0.35f)
                        close()
                    }
                    drawPath(tip, color = Color(0xFF5EB5F7))
                }
            }
        }
        Text(
            text = windTxt,
            color = BridgeText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

private fun buildMapBeamJson(
    state: AppUiState,
    statusLine: String?,
    recenter: Boolean,
): String {
    val (lat, lon) = state.displayPrefs.effectiveLatLon()
    val antIdx = (state.rotor.selectedAntenna - 1).coerceIn(0, 2)
    val slot = state.rotor.antennas.getOrNull(antIdx) ?: AntennaSlot()
    val rotorAz = state.rotor.azSmoothDeg ?: state.rotor.azDeg ?: 0.0
    val displayAz = AntennaMath.displayFromRotor(rotorAz, slot.offsetDeg)
    val opening = slot.openingDeg.coerceIn(0.0, 360.0).let { if (it < 1.0) 30.0 else it }
    val rangeKm = slot.rangeKm.coerceIn(1, 99_999).toDouble()
    val (stroke, fill) = GeoUtils.ANTENNA_BEAM_COLORS[antIdx]

    fun beamAt(bearing: Double): JSONObject {
        val poly = GeoUtils.beamPolygonPoints(lat, lon, bearing, opening, rangeKm)
        val line = GeoUtils.beamCenterLinePoints(lat, lon, bearing, rangeKm)
        return JSONObject()
            .put("polygon", pointsToJson(poly))
            .put("center_line", pointsToJson(line))
            .put("stroke", stroke)
            .put("fill", fill)
    }

    val beams = JSONArray().put(beamAt(displayAz))
    if (slot.dipole) {
        beams.put(beamAt(GeoUtils.wrap360(displayAz + 180.0)))
    }

    val sollDisplay = state.rotor.azCompassTarget
        ?: state.rotor.azTarget?.let { AntennaMath.displayFromRotor(it, slot.offsetDeg) }
    val targetLine = if (sollDisplay != null) {
        pointsToJson(GeoUtils.beamCenterLinePoints(lat, lon, sollDisplay, rangeKm))
    } else {
        null
    }

    val locator = GeoUtils.latLonToMaidenhead(lat, lon, 6)
    val locStr = buildString {
        append("%.4f".format(lat))
        append("° / ")
        append("%.4f".format(lon))
        append("°")
        if (locator.isNotEmpty()) {
            append(" · ")
            append(locator)
        }
    }

    return JSONObject()
        .put("lat", lat)
        .put("lon", lon)
        .put("location_str", locStr)
        .put("opening", opening)
        .put("range_km", rangeKm)
        .put("beams", beams)
        .put("target_bearing_line", targetLine ?: JSONObject.NULL)
        .put("target_bearing_color", "#ff7043")
        .put("recenter", recenter)
        .put("status_line", statusLine ?: "")
        .toString()
}

private fun pointsToJson(pts: List<Pair<Double, Double>>): JSONArray {
    val arr = JSONArray()
    pts.forEach { (la, lo) ->
        arr.put(JSONArray().put(la).put(lo))
    }
    return arr
}
