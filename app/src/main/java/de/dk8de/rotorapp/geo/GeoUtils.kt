package de.dk8de.rotorapp.geo

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Geografische Hilfen für die Antennenkarte (Port aus RotorTcpBridge geo_utils). */
object GeoUtils {
    private const val EARTH_RADIUS_KM = 6371.0

    /** Bridge-Defaults (JO31…). */
    const val DEFAULT_LAT = 49.502651
    const val DEFAULT_LON = 8.375019

    /** Strich / Füllung Antenne 1–3 (wie Bridge ANTENNA_BEAM_COLORS). */
    val ANTENNA_BEAM_COLORS: List<Pair<String, String>> = listOf(
        "#5BA3D0" to "#87CEEB",
        "#66BB6A" to "#C8E6C9",
        "#ae80d9" to "#d8c4f0",
    )

    fun wrap360(deg: Double): Double {
        var d = deg % 360.0
        if (d < 0) d += 360.0
        return d
    }

    /**
     * Effektiver Standort: Locator-Zellenmitte nur wenn Lat/Lon noch Default sind
     * (wie Bridge effective_station_lat_lon).
     */
    fun effectiveStationLatLon(
        lat: Double,
        lon: Double,
        locator: String,
    ): Pair<Double, Double> {
        val loc = locator.trim()
        if (loc.isEmpty()) return lat to lon
        val ll = maidenheadToLatLon(loc) ?: return lat to lon
        val stillDefault =
            kotlin.math.abs(lat - DEFAULT_LAT) <= 1e-5 &&
                kotlin.math.abs(lon - DEFAULT_LON) <= 1e-5
        return if (stillDefault) ll else lat to lon
    }

    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val lat1r = Math.toRadians(lat1)
        val lat2r = Math.toRadians(lat2)
        val dlat = Math.toRadians(lat2 - lat1)
        val dlon = Math.toRadians(lon2 - lon1)
        val a = sin(dlat / 2) * sin(dlat / 2) +
            cos(lat1r) * cos(lat2r) * sin(dlon / 2) * sin(dlon / 2)
        val c = 2 * asin(min(1.0, sqrt(max(0.0, a))))
        return EARTH_RADIUS_KM * c
    }

    /** Peilung Punkt1→Punkt2 in Grad (0=N, 90=O) — Kugel-Formel. */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val lat1r = Math.toRadians(lat1)
        val lat2r = Math.toRadians(lat2)
        val dlon = Math.toRadians(lon2 - lon1)
        val y = sin(dlon) * cos(lat2r)
        val x = cos(lat1r) * sin(lat2r) - sin(lat1r) * cos(lat2r) * cos(dlon)
        return wrap360(Math.toDegrees(atan2(y, x)))
    }

    fun destinationPoint(
        lat: Double,
        lon: Double,
        bearingDegVal: Double,
        distKm: Double,
    ): Pair<Double, Double> {
        val d = distKm / EARTH_RADIUS_KM
        val br = Math.toRadians(bearingDegVal)
        val latR = Math.toRadians(lat)
        val lonR = Math.toRadians(lon)
        val lat2R = asin(sin(latR) * cos(d) + cos(latR) * sin(d) * cos(br))
        val lon2R = lonR + atan2(
            sin(br) * sin(d) * cos(latR),
            cos(d) - sin(latR) * sin(lat2R),
        )
        return Math.toDegrees(lat2R) to Math.toDegrees(lon2R)
    }

    fun maidenheadToLatLon(grid: String): Pair<Double, Double>? {
        var s = grid.trim().uppercase().replace("\\s+".toRegex(), "")
        if (s.length < 2) return null
        if (s.length % 2 == 1) s = s.dropLast(1)
        return try {
            if (s[0] !in 'A'..'R' || s[1] !in 'A'..'R') return null
            var lon = -180.0 + (s[0] - 'A') * 20.0
            var lat = -90.0 + (s[1] - 'A') * 10.0
            val n = s.length
            if (n >= 4) {
                if (!s[2].isDigit() || !s[3].isDigit()) return null
                lon += (s[2] - '0') * 2.0
                lat += (s[3] - '0') * 1.0
            }
            if (n >= 6) {
                if (s[4] !in 'A'..'X' || s[5] !in 'A'..'X') return null
                lon += (s[4] - 'A') * (2.0 / 24)
                lat += (s[5] - 'A') * (1.0 / 24)
            }
            if (n >= 8) {
                if (!s[6].isDigit() || !s[7].isDigit()) return null
                lon += (s[6] - '0') * (2.0 / 240)
                lat += (s[7] - '0') * (1.0 / 240)
            }
            if (n >= 10) {
                if (s[8] !in 'A'..'X' || s[9] !in 'A'..'X') return null
                lon += (s[8] - 'A') * (2.0 / 5760)
                lat += (s[9] - 'A') * (1.0 / 5760)
            }
            when (n) {
                2 -> {
                    lon += 10; lat += 5
                }
                4 -> {
                    lon += 1; lat += 0.5
                }
                6 -> {
                    lon += (2.0 / 24) / 2; lat += (1.0 / 24) / 2
                }
                8 -> {
                    lon += (2.0 / 240) / 2; lat += (1.0 / 240) / 2
                }
                10 -> {
                    lon += (2.0 / 5760) / 2; lat += (1.0 / 5760) / 2
                }
            }
            lat to lon
        } catch (_: Exception) {
            null
        }
    }

    fun latLonToMaidenhead(lat: Double, lon: Double, nChars: Int = 6): String {
        val n = if (nChars in listOf(2, 4, 6, 8, 10)) nChars else 6
        val lonF = lon.coerceIn(-180.0, 179.999999999)
        val latF = lat.coerceIn(-90.0, 89.999999999)
        var lon180 = lonF + 180.0
        var lat90 = latF + 90.0
        val a = (lon180 / 20.0).toInt()
        val b = (lat90 / 10.0).toInt()
        if (a !in 0..17 || b !in 0..17) return ""
        var s = "${('A' + a)}${('A' + b)}"
        if (n <= 2) return s
        lon180 -= a * 20.0
        lat90 -= b * 10.0
        val d2 = (lon180 / 2.0).toInt().coerceIn(0, 9)
        val d3 = (lat90 / 1.0).toInt().coerceIn(0, 9)
        s += "$d2$d3"
        if (n <= 4) return s
        lon180 -= d2 * 2.0
        lat90 -= d3 * 1.0
        var stepLon = 2.0 / 24.0
        var stepLat = 1.0 / 24.0
        val c4 = (lon180 / stepLon).toInt().coerceIn(0, 23)
        val c5 = (lat90 / stepLat).toInt().coerceIn(0, 23)
        s += "${('A' + c4)}${('A' + c5)}"
        if (n <= 6) return s
        lon180 -= c4 * stepLon
        lat90 -= c5 * stepLat
        stepLon = 2.0 / 240.0
        stepLat = 1.0 / 240.0
        val d6 = (lon180 / stepLon).toInt().coerceIn(0, 9)
        val d7 = (lat90 / stepLat).toInt().coerceIn(0, 9)
        s += "$d6$d7"
        if (n <= 8) return s
        lon180 -= d6 * stepLon
        lat90 -= d7 * stepLat
        stepLon = 2.0 / 5760.0
        stepLat = 1.0 / 5760.0
        val c8 = (lon180 / stepLon).toInt().coerceIn(0, 23)
        val c9 = (lat90 / stepLat).toInt().coerceIn(0, 23)
        s += "${('A' + c8)}${('A' + c9)}"
        return s
    }

    private fun pointsAlongGreatCircle(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
        nSeg: Int,
    ): List<Pair<Double, Double>> {
        if (nSeg < 1) return listOf(lat1 to lon1, lat2 to lon2)
        val lat1r = Math.toRadians(lat1)
        val lon1r = Math.toRadians(lon1)
        val lat2r = Math.toRadians(lat2)
        val lon2r = Math.toRadians(lon2)
        val x1 = cos(lat1r) * cos(lon1r)
        val y1 = cos(lat1r) * sin(lon1r)
        val z1 = sin(lat1r)
        val x2 = cos(lat2r) * cos(lon2r)
        val y2 = cos(lat2r) * sin(lon2r)
        val z2 = sin(lat2r)
        val dot = (x1 * x2 + y1 * y2 + z1 * z2).coerceIn(-1.0, 1.0)
        val ang = kotlin.math.acos(dot)
        if (ang < 1e-9) return listOf(lat1 to lon1, lat2 to lon2)
        val result = ArrayList<Pair<Double, Double>>(nSeg + 1)
        for (i in 0..nSeg) {
            val t = i.toDouble() / nSeg
            val a = sin((1 - t) * ang) / sin(ang)
            val b = sin(t * ang) / sin(ang)
            val x = a * x1 + b * x2
            val y = a * y1 + b * y2
            val z = a * z1 + b * z2
            val latR = atan2(z, sqrt(x * x + y * y))
            val lonR = atan2(y, x)
            result.add(Math.toDegrees(latR) to Math.toDegrees(lonR))
        }
        return result
    }

    fun beamPolygonPoints(
        lat: Double,
        lon: Double,
        azimuthDeg: Double,
        openingDeg: Double,
        rangeKm: Double,
        steps: Int = 24,
    ): List<Pair<Double, Double>> {
        val half = openingDeg / 2.0
        var startBearing = wrap360(azimuthDeg - half)
        var endBearing = wrap360(azimuthDeg + half)
        if (endBearing <= startBearing) endBearing += 360.0
        val radialSeg = max(3, min(20, (rangeKm / 500).toInt() + 1))
        val arcSeg = max(2, min(15, (rangeKm / 1000).toInt() + 1))
        val arcPts = (0..steps).map { i ->
            val t = i.toDouble() / steps
            val b = (startBearing + t * (endBearing - startBearing)) % 360.0
            destinationPoint(lat, lon, b, rangeKm)
        }
        val points = ArrayList<Pair<Double, Double>>()
        points.add(lat to lon)
        pointsAlongGreatCircle(lat, lon, arcPts[0].first, arcPts[0].second, radialSeg)
            .drop(1).forEach { points.add(it) }
        for (j in 1 until arcPts.size) {
            pointsAlongGreatCircle(
                arcPts[j - 1].first, arcPts[j - 1].second,
                arcPts[j].first, arcPts[j].second,
                arcSeg,
            ).drop(1).forEach { points.add(it) }
        }
        pointsAlongGreatCircle(arcPts.last().first, arcPts.last().second, lat, lon, radialSeg)
            .drop(1).forEach { points.add(it) }
        return points
    }

    fun beamCenterLinePoints(
        lat: Double,
        lon: Double,
        azimuthDeg: Double,
        rangeKm: Double,
        nSeg: Int = 15,
    ): List<Pair<Double, Double>> {
        val end = destinationPoint(lat, lon, azimuthDeg, rangeKm)
        return pointsAlongGreatCircle(
            lat, lon, end.first, end.second,
            max(2, min(nSeg, (rangeKm / 300).toInt() + 1)),
        )
    }
}
