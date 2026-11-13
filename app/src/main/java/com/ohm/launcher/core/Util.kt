package com.ohm.launcher.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object Http {
    private const val UA = "OhmLauncher/0.1 (Android head-unit launcher)"

    suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "application/json")
        try {
            val code = c.responseCode
            if (code !in 200..299) error("HTTP $code")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** Form POST (used for long Overpass queries). */
    suspend fun post(url: String, form: String): String = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 30000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        try {
            c.outputStream.use { it.write(form.toByteArray()) }
            val code = c.responseCode
            if (code !in 200..299) error("HTTP $code")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}

data class LatLon(val lat: Double, val lon: Double)

object GeoMath {
    private const val R = 6371000.0

    fun dist(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val s = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * R * atan2(sqrt(s), sqrt(1 - s))
    }

    /** Initial bearing a→b in degrees 0..360. */
    fun bearing(a: LatLon, b: LatLon): Double {
        val la1 = Math.toRadians(a.lat); val la2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(la2)
        val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    fun lerp(a: LatLon, b: LatLon, t: Double) = LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)

    /** Shortest signed angle from a to b (degrees). */
    fun angleDiff(a: Double, b: Double): Double {
        var d = (b - a) % 360
        if (d > 180) d -= 360
        if (d < -180) d += 360
        return d
    }

    /** Cumulative distances along a polyline. */
    fun cumulative(pts: List<LatLon>): DoubleArray {
        val out = DoubleArray(pts.size)
        for (i in 1 until pts.size) out[i] = out[i - 1] + dist(pts[i - 1], pts[i])
        return out
    }

    /** Point and segment-bearing at distance d along a polyline. */
    fun along(pts: List<LatLon>, cum: DoubleArray, d: Double): Pair<LatLon, Double> {
        if (pts.size < 2) return (pts.firstOrNull() ?: LatLon(0.0, 0.0)) to 0.0
        if (d <= 0) return pts[0] to bearing(pts[0], pts[1])
        if (d >= cum.last()) return pts.last() to bearing(pts[pts.size - 2], pts.last())
        var lo = 0; var hi = cum.size - 1
        while (hi - lo > 1) { val m = (lo + hi) / 2; if (cum[m] <= d) lo = m else hi = m }
        val seg = cum[hi] - cum[lo]
        val t = if (seg <= 0) 0.0 else (d - cum[lo]) / seg
        return lerp(pts[lo], pts[hi], t) to bearing(pts[lo], pts[hi])
    }

    /**
     * Projects p onto the polyline, searching segments around [hint] first.
     * Returns (distance along line, distance from line in m).
     */
    fun project(pts: List<LatLon>, cum: DoubleArray, p: LatLon, hint: Double = 0.0): Pair<Double, Double> {
        var best = Double.MAX_VALUE; var bestAlong = 0.0
        val cosLat = cos(Math.toRadians(p.lat))
        for (i in 0 until pts.size - 1) {
            if (cum[i] < hint - 500 || cum[i] > hint + 3000) continue
            val a = pts[i]; val b = pts[i + 1]
            val ax = (a.lon - p.lon) * cosLat; val ay = a.lat - p.lat
            val bx = (b.lon - p.lon) * cosLat; val by = b.lat - p.lat
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val q = lerp(a, b, t)
            val dd = dist(p, q)
            if (dd < best) { best = dd; bestAlong = cum[i] + (cum[i + 1] - cum[i]) * t }
        }
        return bestAlong to best
    }
}

object Fmt {
    fun distance(m: Double, metric: Boolean): String = if (metric) {
        when {
            m < 1000 -> "${((m / 10).roundToInt() * 10).coerceAtLeast(0)} m"
            m < 10000 -> String.format(java.util.Locale.US, "%.1f km", m / 1000)
            else -> "${(m / 1000).roundToInt()} km"
        }
    } else {
        val mi = m / 1609.344
        if (mi < 0.1) "${(m * 3.28084 / 10).roundToInt() * 10} ft"
        else String.format(java.util.Locale.US, "%.1f mi", mi)
    }

    fun duration(s: Double): String {
        val min = (s / 60).roundToInt()
        return if (min < 60) "$min min" else "${min / 60} h ${min % 60} min"
    }

    fun speed(kmh: Float, metric: Boolean): Int = if (metric) kmh.roundToInt() else (kmh / 1.609344f).roundToInt()

    fun temp(c: Double, celsius: Boolean): String = if (celsius) "${c.roundToInt()}°" else "${(c * 9 / 5 + 32).roundToInt()}°"

    /** Fuel consumption from L/100 km into the chosen unit. */
    fun consumption(l100: Double, unit: String): String = when (unit) {
        "kml" -> String.format(java.util.Locale.US, "%.1f km/L", 100 / l100)
        "mpgus" -> String.format(java.util.Locale.US, "%.1f MPG", 235.215 / l100)
        "mpguk" -> String.format(java.util.Locale.US, "%.1f MPG", 282.481 / l100)
        else -> String.format(java.util.Locale.US, "%.1f L/100 km", l100)
    }

    fun volume(litres: Double, distanceKm: Boolean): String =
        if (distanceKm) String.format(java.util.Locale.US, "%.1f L", litres) else String.format(java.util.Locale.US, "%.2f gal", litres / 3.78541)

    fun pressure(bar: Double, unit: String): String = when (unit) {
        "psi" -> "${(bar * 14.5038).roundToInt()} psi"
        "kpa" -> "${(bar * 100).roundToInt()} kPa"
        else -> String.format(java.util.Locale.US, "%.1f bar", bar)
    }

    fun money(amount: Double, currency: String): String = runCatching {
        val f = java.text.NumberFormat.getCurrencyInstance()
        f.currency = java.util.Currency.getInstance(currency)
        f.format(amount)
    }.getOrElse { String.format(java.util.Locale.US, "%.2f %s", amount, currency) }
}
