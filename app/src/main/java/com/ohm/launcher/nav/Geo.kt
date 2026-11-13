package com.ohm.launcher.nav

import com.ohm.launcher.core.GeoMath
import com.ohm.launcher.core.Http
import com.ohm.launcher.core.LatLon
import org.json.JSONArray
import org.json.JSONObject

data class Place(val name: String, val address: String, val pos: LatLon, val category: String? = null) {
    fun encode() = listOf(name, address, pos.lat, pos.lon, category ?: "").joinToString("~")

    companion object {
        fun decode(s: String): Place? = runCatching {
            val p = s.split("~")
            Place(p[0], p[1], LatLon(p[2].toDouble(), p[3].toDouble()), p.getOrNull(4)?.ifEmpty { null })
        }.getOrNull()
    }
}

data class Lane(val valid: Boolean, val indications: List<String>)

data class Step(
    val type: String,
    val modifier: String?,
    val name: String,
    val exit: Int?,
    val pos: LatLon,
    /** Distance from route start to this maneuver. */
    val atM: Double,
    val lanes: List<Lane>,
)

data class Route(
    val points: List<LatLon>,
    val cum: DoubleArray,
    val distanceM: Double,
    val durationS: Double,
    val steps: List<Step>,
    /** Speed limit (km/h) per polyline segment, null where unknown. */
    val maxspeed: List<Int?>,
    /** OpenStreetMap node id per polyline point (for looking up speed limits). */
    val nodes: List<Long> = emptyList(),
) {
    fun speedLimitAt(d: Double): Int? {
        if (maxspeed.isEmpty()) return null
        var lo = 0; var hi = cum.size - 1
        while (hi - lo > 1) { val m = (lo + hi) / 2; if (cum[m] <= d) lo = m else hi = m }
        return maxspeed.getOrNull(lo)
    }
}

data class SpeedCamera(val pos: LatLon, val atM: Double, val maxspeed: Int?)

/**
 * Free, key-less web services:
 *  - Photon (komoot) for search & reverse geocoding
 *  - OSRM public demo server for routing
 *  - Overpass for categories (fuel, parking…) and speed cameras
 * They are fine for testing; for daily use you'd move to your own/paid endpoints (SPEC.md §3).
 */
object Geo {
    suspend fun search(q: String, near: LatLon, lang: String): List<Place> {
        val l = if (lang == "pt") "default" else "en"
        val url = "https://photon.komoot.io/api/?q=${Http.enc(q)}&lat=${near.lat}&lon=${near.lon}&limit=8&lang=$l"
        val feats = JSONObject(Http.get(url)).getJSONArray("features")
        return (0 until feats.length()).map { i -> photonPlace(feats.getJSONObject(i)) }
    }

    suspend fun reverse(p: LatLon): String? {
        val url = "https://photon.komoot.io/reverse?lat=${p.lat}&lon=${p.lon}&limit=1"
        val feats = JSONObject(Http.get(url)).getJSONArray("features")
        if (feats.length() == 0) return null
        val pr = feats.getJSONObject(0).getJSONObject("properties")
        return pr.optString("district").ifEmpty { pr.optString("city") }.ifEmpty { pr.optString("county") }.ifEmpty { null }
    }

    private fun photonPlace(f: JSONObject): Place {
        val c = f.getJSONObject("geometry").getJSONArray("coordinates")
        val p = f.getJSONObject("properties")
        val name = p.optString("name").ifEmpty { listOf(p.optString("street"), p.optString("housenumber")).filter { it.isNotEmpty() }.joinToString(" ") }
        val addr = listOf(
            listOf(p.optString("street"), p.optString("housenumber")).filter { it.isNotEmpty() }.joinToString(" "),
            p.optString("city"), p.optString("country"),
        ).filter { it.isNotEmpty() && it != name }.joinToString(", ")
        return Place(name.ifEmpty { addr }, addr, LatLon(c.getDouble(1), c.getDouble(0)))
    }

    suspend fun routes(from: LatLon, to: LatLon, avoidTolls: Boolean, avoidHighways: Boolean): List<Route> {
        val ex = listOfNotNull("toll".takeIf { avoidTolls }, "motorway".takeIf { avoidHighways })
        val base = "https://router.project-osrm.org/route/v1/driving/${from.lon},${from.lat};${to.lon},${to.lat}" +
            // The public OSRM server no longer serves "maxspeed"; node ids let us look limits up in OSM.
            "?overview=full&geometries=geojson&steps=true&alternatives=true&annotations=nodes"
        val json = try {
            Http.get(if (ex.isEmpty()) base else "$base&exclude=${ex.joinToString(",")}")
        } catch (e: Exception) {
            if (ex.isEmpty()) throw e else Http.get(base) // server may not support exclude
        }
        val o = JSONObject(json)
        if (o.optString("code") != "Ok") error(o.optString("message", "No route"))
        val arr = o.getJSONArray("routes")
        return (0 until arr.length()).map { parseRoute(arr.getJSONObject(it)) }
    }

    private fun parseRoute(r: JSONObject): Route {
        val coords = r.getJSONObject("geometry").getJSONArray("coordinates")
        val pts = (0 until coords.length()).map { val c = coords.getJSONArray(it); LatLon(c.getDouble(1), c.getDouble(0)) }
        val cum = GeoMath.cumulative(pts)
        val leg = r.getJSONArray("legs").getJSONObject(0)
        val stepsJ = leg.getJSONArray("steps")
        var acc = 0.0
        val steps = (0 until stepsJ.length()).map { i ->
            val s = stepsJ.getJSONObject(i)
            val m = s.getJSONObject("maneuver")
            val loc = m.getJSONArray("location")
            val lanesJ = s.optJSONArray("intersections")?.optJSONObject(0)?.optJSONArray("lanes")
            val st = Step(
                type = m.optString("type"),
                modifier = m.optString("modifier").ifEmpty { null },
                name = s.optString("name"),
                exit = if (m.has("exit")) m.optInt("exit") else null,
                pos = LatLon(loc.getDouble(1), loc.getDouble(0)),
                atM = acc,
                lanes = lanesJ?.let { la -> (0 until la.length()).map { j -> la.getJSONObject(j).let { lo -> Lane(lo.optBoolean("valid"), lo.optJSONArray("indications").strings()) } } } ?: emptyList(),
            )
            acc += s.optDouble("distance", 0.0)
            st
        }
        val ms = leg.optJSONObject("annotation")?.optJSONArray("maxspeed")
        val maxspeed = ms?.let { a -> (0 until a.length()).map { j -> a.optJSONObject(j)?.takeIf { it.has("speed") }?.let { x -> x.optInt("speed").let { sp -> if (x.optString("unit") == "mph") (sp * 1.609).toInt() else sp } } } } ?: emptyList()
        val nodesJ = leg.optJSONObject("annotation")?.optJSONArray("nodes")
        val nodes = nodesJ?.let { a -> (0 until a.length()).map { a.getLong(it) } } ?: emptyList()
        return Route(pts, cum, r.getDouble("distance"), r.getDouble("duration"), steps, maxspeed, nodes)
    }

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }

    /** Category search around a point via Overpass. */
    suspend fun nearby(category: String, near: LatLon, radiusM: Int = 4000): List<Place> {
        val tag = when (category) {
            "fuel" -> "\"amenity\"=\"fuel\""
            "parking" -> "\"amenity\"=\"parking\""
            "ev" -> "\"amenity\"=\"charging_station\""
            else -> "\"amenity\"~\"restaurant|cafe|fast_food\""
        }
        val q = "[out:json][timeout:15];nwr[$tag](around:$radiusM,${near.lat},${near.lon});out center 25;"
        val o = JSONObject(Http.get("https://overpass-api.de/api/interpreter?data=${Http.enc(q)}"))
        val els = o.getJSONArray("elements")
        return (0 until els.length()).mapNotNull { i ->
            val e = els.getJSONObject(i)
            val lat = e.optDouble("lat", e.optJSONObject("center")?.optDouble("lat") ?: Double.NaN)
            val lon = e.optDouble("lon", e.optJSONObject("center")?.optDouble("lon") ?: Double.NaN)
            if (lat.isNaN()) return@mapNotNull null
            val t = e.optJSONObject("tags") ?: JSONObject()
            val name = t.optString("name").ifEmpty { t.optString("brand") }.ifEmpty { t.optString("operator") }.ifEmpty { category }
            val addr = listOf(t.optString("addr:street"), t.optString("addr:housenumber")).filter { it.isNotEmpty() }.joinToString(" ")
            Place(name, addr, LatLon(lat, lon), category)
        }.sortedBy { GeoMath.dist(near, it.pos) }
    }

    /**
     * Speed limit per route segment from OpenStreetMap: the ways that contain the route's nodes,
     * with their maxspeed tag (numbers, "xx mph", and implicit values like "PT:urban").
     */
    suspend fun speedLimits(route: Route): List<Int?> {
        if (route.nodes.size < 2) return emptyList()
        val ids = route.nodes.distinct().take(6000)
        val q = "[out:json][timeout:25];node(id:${ids.joinToString(",")});way(bn)[maxspeed];out body;"
        val o = JSONObject(Http.post("https://overpass-api.de/api/interpreter", "data=" + Http.enc(q)))
        val els = o.getJSONArray("elements")
        // node pair (a,b) on the same way → that way's limit
        val pair = HashMap<Long, HashMap<Long, Int>>()
        for (i in 0 until els.length()) {
            val w = els.getJSONObject(i)
            val limit = parseMaxspeed(w.optJSONObject("tags")?.optString("maxspeed")) ?: continue
            val nd = w.optJSONArray("nodes") ?: continue
            for (k in 0 until nd.length() - 1) {
                val a = nd.getLong(k); val b = nd.getLong(k + 1)
                pair.getOrPut(a) { HashMap() }[b] = limit
                pair.getOrPut(b) { HashMap() }[a] = limit
            }
        }
        return (0 until route.nodes.size - 1).map { i -> pair[route.nodes[i]]?.get(route.nodes[i + 1]) }
    }

    fun parseMaxspeed(v: String?): Int? {
        if (v.isNullOrBlank()) return null
        val s = v.trim().lowercase()
        Regex("""^(\d+)\s*mph""").find(s)?.let { return (it.groupValues[1].toInt() * 1.609).toInt() }
        s.toIntOrNull()?.let { return it }
        Regex("""^(\d+)""").find(s)?.let { return it.groupValues[1].toInt() }
        return when {
            s.endsWith(":urban") -> 50
            s.endsWith(":rural") -> 90
            s.endsWith(":motorway") -> 120
            s.endsWith(":trunk") -> 100
            s.endsWith(":living_street") || s == "walk" -> 20
            else -> null
        }
    }

    /** Fixed speed cameras along a route (OSM highway=speed_camera). */
    suspend fun speedCameras(route: Route): List<SpeedCamera> {
        val s = route.points.minOf { it.lat }; val n = route.points.maxOf { it.lat }
        val w = route.points.minOf { it.lon }; val e = route.points.maxOf { it.lon }
        if ((n - s) * (e - w) > 1.0) return emptyList() // too big for a public Overpass query
        val q = "[out:json][timeout:15];node[\"highway\"=\"speed_camera\"]($s,$w,$n,$e);out 200;"
        val o = JSONObject(Http.get("https://overpass-api.de/api/interpreter?data=${Http.enc(q)}"))
        val els = o.getJSONArray("elements")
        return (0 until els.length()).mapNotNull { i ->
            val el = els.getJSONObject(i)
            val p = LatLon(el.getDouble("lat"), el.getDouble("lon"))
            val full = projectFull(route, p)
            if (full.second > 40) null
            else SpeedCamera(p, full.first, el.optJSONObject("tags")?.optString("maxspeed")?.toIntOrNull())
        }.sortedBy { it.atM }
    }

    private fun projectFull(route: Route, p: LatLon): Pair<Double, Double> {
        var best = Double.MAX_VALUE to 0.0
        var hint = 0.0
        while (hint <= route.distanceM) {
            val r = GeoMath.project(route.points, route.cum, p, hint)
            if (r.second < best.first) best = r.second to r.first
            hint += 3000
        }
        return best.second to best.first
    }
}
