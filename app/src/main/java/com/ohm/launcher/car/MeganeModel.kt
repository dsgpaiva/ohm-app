package com.ohm.launcher.car

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * Detailed Renault Mégane III (2008–2016) generated from its real proportions — Grandtour (estate)
 * by default, plus hatch and coupé variants. Pure geometry, no model file: the body is lofted
 * through ~30 measured cross-sections; lights, grille, plates, seams, handles, arches, roof rails,
 * mirrors and 5-spoke wheels are added as decals / small 3D parts; doors, bonnet and tailgate are
 * separate hinged parts. Units: metres. x = right (+) / left (−), y = up, z = forward.
 *
 * Grandtour: length ≈ 4.56 m, width ≈ 1.80 m, height ≈ 1.47 m (1.51 with rails), wheelbase ≈ 2.70 m.
 */

internal class Sec(
    val z: Double,
    /** lowest body point (sill / bumper bottom) */
    val bottom: Double,
    /** max half-width (door bulge) */
    val hw: Double,
    /** beltline / fender line / bonnet edge height */
    val belt: Double,
    /** roof edge (cabin) or bonnet/deck edge height */
    val edge: Double,
    /** half-width at the roof edge */
    val rw: Double,
    /** centre-line top height */
    val crown: Double,
)

private fun l(a: Double, b: Double, t: Double) = a + (b - a) * t

internal data class P2(val x: Double, val y: Double)

/** The lofted body and lookup helpers used to place details exactly on its surface. */
internal class Profile(secs: List<Sec>) {
    val secs = secs.sortedBy { it.z }
    val zMin = this.secs.first().z
    val zMax = this.secs.last().z

    fun at(z: Double): Sec {
        if (z <= zMin) return secs.first()
        if (z >= zMax) return secs.last()
        val i = secs.indexOfLast { it.z <= z }.coerceIn(0, secs.size - 2)
        val a = secs[i]; val b = secs[i + 1]
        val t = (z - a.z) / (b.z - a.z)
        return Sec(z, l(a.bottom, b.bottom, t), l(a.hw, b.hw, t), l(a.belt, b.belt, t), l(a.edge, b.edge, t), l(a.rw, b.rw, t), l(a.crown, b.crown, t))
    }

    /** 11 points of the right half, bottom-centre → top-centre (y made monotonic from index 1). */
    fun ring(s: Sec): List<P2> {
        val cabin = ((s.edge - s.belt) / 0.30).coerceIn(0.0, 1.0)
        val wy = min(0.56, s.belt - 0.14)
        val hb = s.hw - 0.05
        val hbelt = s.hw - 0.035 - 0.015 * cabin
        val pts = mutableListOf(
            P2(0.0, s.bottom + 0.02),
            P2(hb, s.bottom),
            P2(s.hw - 0.008, l(s.bottom, wy, 0.45)),
            P2(s.hw, wy),
            P2(s.hw - 0.012, s.belt - 0.10),
            P2(hbelt, s.belt),
            P2((hbelt + s.rw) / 2 + 0.012 * cabin, l(s.belt, s.edge, 0.5)),
            P2(s.rw + 0.025 * cabin, s.edge - 0.04 * cabin),
            P2(s.rw - 0.045, s.edge + 0.014 * cabin + 0.004),
            P2(s.rw * 0.5, s.crown - 0.008),
            P2(0.0, s.crown),
        )
        for (i in 2 until pts.size) if (pts[i].y < pts[i - 1].y + 0.001) pts[i] = P2(pts[i].x, pts[i - 1].y + 0.001)
        return pts
    }

    fun ringAt(z: Double) = ring(at(z))

    /** Half-width of the body at height [y] in section [z], or null outside the body. */
    fun halfWidth(z: Double, y: Double): Double? {
        val r = ringAt(z)
        for (i in 1 until r.size - 1) {
            val a = r[i]; val b = r[i + 1]
            if (y >= a.y && y <= b.y) return l(a.x, b.x, if (b.y == a.y) 0.0 else (y - a.y) / (b.y - a.y))
        }
        return null
    }

    /** Height of the top surface (bonnet / roof / deck) at half-width |x|, or null beyond the shoulder. */
    fun topY(z: Double, x: Double): Double? {
        val ax = abs(x)
        val r = ringAt(z)
        for (i in 5 until r.size - 1) {
            val a = r[i]; val b = r[i + 1]
            if (ax <= a.x && ax >= b.x) return l(a.y, b.y, if (a.x == b.x) 0.0 else (a.x - ax) / (a.x - b.x))
        }
        return null
    }

    /** z of the front surface at (x, y), scanning back from the nose. */
    fun frontZ(x: Double, y: Double): Double? {
        var z = zMax
        // only the nose: details whose point would land far back (on the bonnet/wing) are dropped instead of spiking
        while (z > zMax - 0.40) { val w = halfWidth(z, y); if (w != null && w >= abs(x)) return z; z -= 0.003 }
        return null
    }

    fun rearZ(x: Double, y: Double): Double? {
        var z = zMin
        while (z < zMin + 0.40) { val w = halfWidth(z, y); if (w != null && w >= abs(x)) return z; z += 0.003 }
        return null
    }

    fun side(z: Double, y: Double, s: Double, off: Double = 0.006): V3? = halfWidth(z, y)?.let { V3(s * (it + off), y, z) }
    fun front(x: Double, y: Double, off: Double = 0.006): V3? = frontZ(x, y)?.let { V3(x, y, it + off) }
    fun rear(x: Double, y: Double, off: Double = 0.006): V3? = rearZ(x, y)?.let { V3(x, y, it - off) }
    fun top(x: Double, z: Double, off: Double = 0.006): V3? = topY(z, x)?.let { V3(x, it + off, z) }
}

// ============================================================ body variants

private fun S(z: Double, bottom: Double, hw: Double, belt: Double, edge: Double, rw: Double, crown: Double) = Sec(z, bottom, hw, belt, edge, rw, crown)

/** Grandtour: long flat roof, near-vertical tailgate, rising rear side-window line. */
private val ESTATE = listOf(
    S(-2.28, 0.36, 0.55, 0.50, 0.52, 0.47, 0.53),
    S(-2.26, 0.34, 0.72, 0.62, 0.64, 0.62, 0.65),
    S(-2.22, 0.31, 0.80, 0.80, 0.82, 0.70, 0.83),
    S(-2.16, 0.29, 0.83, 0.92, 1.05, 0.66, 1.06),
    S(-2.08, 0.27, 0.845, 0.96, 1.30, 0.62, 1.34),
    S(-2.00, 0.25, 0.86, 0.98, 1.35, 0.645, 1.40),
    S(-1.92, 0.24, 0.87, 0.99, 1.37, 0.655, 1.42),
    S(-1.75, 0.23, 0.878, 1.00, 1.39, 0.665, 1.435),
    S(-1.55, 0.21, 0.885, 1.01, 1.40, 0.67, 1.445),
    S(-1.35, 0.20, 0.89, 1.02, 1.41, 0.675, 1.455),
    S(-1.15, 0.19, 0.893, 1.025, 1.418, 0.678, 1.463),
    S(-1.05, 0.185, 0.895, 1.025, 1.42, 0.68, 1.465),
    S(-0.95, 0.18, 0.896, 1.02, 1.425, 0.682, 1.468),
    S(-0.70, 0.18, 0.898, 1.012, 1.43, 0.684, 1.472),
    S(-0.40, 0.18, 0.90, 1.00, 1.435, 0.685, 1.475),
    S(-0.05, 0.18, 0.90, 0.99, 1.43, 0.686, 1.468),
    S(0.05, 0.18, 0.90, 0.988, 1.42, 0.687, 1.458),
    S(0.25, 0.18, 0.90, 0.982, 1.36, 0.70, 1.395),
    S(0.50, 0.18, 0.90, 0.975, 1.24, 0.725, 1.27),
    S(0.75, 0.185, 0.898, 0.965, 1.11, 0.755, 1.135),
    S(0.95, 0.19, 0.895, 0.952, 1.00, 0.79, 1.01),
    S(1.10, 0.19, 0.89, 0.93, 0.95, 0.80, 0.985),
    S(1.35, 0.20, 0.885, 0.905, 0.925, 0.79, 0.955),
    S(1.60, 0.22, 0.875, 0.87, 0.89, 0.775, 0.92),
    S(1.85, 0.24, 0.86, 0.835, 0.855, 0.75, 0.88),
    S(2.05, 0.245, 0.83, 0.80, 0.82, 0.71, 0.845),
    S(2.18, 0.26, 0.775, 0.765, 0.785, 0.65, 0.81),
    S(2.24, 0.27, 0.69, 0.735, 0.755, 0.56, 0.775),
    S(2.28, 0.30, 0.46, 0.70, 0.72, 0.38, 0.735),
)

/** 5-door hatch: shorter rear overhang, roof falling into a raked tailgate. */
private val HATCH = ESTATE.filter { it.z > -1.4 } + listOf(
    S(-1.55, 0.21, 0.885, 1.01, 1.395, 0.665, 1.435),
    S(-1.75, 0.23, 0.878, 1.0, 1.34, 0.645, 1.38),
    S(-1.90, 0.25, 0.865, 0.985, 1.24, 0.62, 1.275),
    S(-2.00, 0.27, 0.85, 0.96, 1.10, 0.64, 1.12),
    S(-2.07, 0.30, 0.82, 0.86, 0.88, 0.70, 0.89),
    S(-2.12, 0.33, 0.74, 0.66, 0.68, 0.64, 0.69),
    S(-2.15, 0.36, 0.55, 0.52, 0.54, 0.47, 0.55),
)

/** Coupé: the hatch with a lower, faster roof. */
private val COUPE = HATCH.map { s ->
    if (s.edge - s.belt > 0.12) {
        val drop = 0.065 * ((0.95 - s.z) / 1.0).coerceIn(0.0, 1.0)
        S(s.z, s.bottom, s.hw, s.belt, s.edge - drop, s.rw - 0.01, s.crown - drop)
    } else s
}

// ============================================================ model assembly

/** Static parts per body type, built once and cached. */
internal class MeganeModel(val profile: Profile, val estate: Boolean) {
    val body = ArrayList<Face>()        // layer 1 (sorted)
    val decals = ArrayList<Face>()      // layer 2 (drawn in order)
    val roof = ArrayList<Face>()        // layer 3 (rails, antenna)
    /** wheels and mirrors, tagged by side (−1 left, +1 right) */
    val outer = ArrayList<Pair<Int, Face>>()
    /** wheel group id → arch opening outline (near wheels are clipped to it) */
    val arches = HashMap<Int, List<V3>>()
    lateinit var frontDoor: DoorTpl
    lateinit var rearDoor: DoorTpl
    lateinit var hood: PartTpl
    lateinit var tailgate: PartTpl

    /** faces = the panel (identical to the body when closed); details = handle, plate… (always drawn) */
    class PartTpl(val faces: List<Face>, val aperture: List<Face>, val hinge: V3, val details: List<Face> = emptyList())
    class DoorTpl(val left: PartTpl, val right: PartTpl)
}

private val cache = HashMap<String, MeganeModel>()

internal fun meganeModel(bodyType: String): MeganeModel = synchronized(cache) {
    cache.getOrPut(bodyType) {
        val secs = when (bodyType) { "hatch" -> HATCH; "coupe" -> COUPE; else -> ESTATE }
        buildModel(Profile(secs), bodyType == "estate" || bodyType !in listOf("hatch", "coupe"))
    }
}

private val CENTER = V3(0.0, 0.7, 0.0)

private fun face(pts: List<V3>, mat: Mat, layer: Int, ref: V3 = CENTER, doubleSided: Boolean = false, open: Float = 0f): Face? {
    val clean = ArrayList<V3>(pts.size)
    for (p in pts) if (clean.isEmpty() || (p - clean.last()).let { it.dot(it) } > 1e-10) clean += p
    if (clean.size > 2 && (clean.first() - clean.last()).let { it.dot(it) } < 1e-10) clean.removeAt(clean.lastIndex)
    if (clean.size < 3) return null
    // Newell normal (also rejects zero-area polygons)
    var nx = 0.0; var ny = 0.0; var nz = 0.0
    for (k in clean.indices) {
        val a = clean[k]; val b = clean[(k + 1) % clean.size]
        nx += (a.y - b.y) * (a.z + b.z); ny += (a.z - b.z) * (a.x + b.x); nz += (a.x - b.x) * (a.y + b.y)
    }
    val n = V3(nx, ny, nz)
    if (n.dot(n) < 1e-12) return null
    val c = clean.fold(V3(0.0, 0.0, 0.0)) { a, b -> a + b } * (1.0 / clean.size)
    val oriented = if (n.dot(c - ref) < 0) clean.reversed() else clean
    return Face(oriented, mat, layer, doubleSided, open)
}

/** A polygon given in some 2D space, densified so it follows the curved surface when projected. */
private fun dense(poly: List<Pair<Double, Double>>, step: Double = 0.025): List<Pair<Double, Double>> {
    val out = ArrayList<Pair<Double, Double>>()
    for (i in poly.indices) {
        val a = poly[i]; val b = poly[(i + 1) % poly.size]
        val len = kotlin.math.hypot(b.first - a.first, b.second - a.second)
        val n = max(1, (len / step).toInt())
        for (k in 0 until n) out += Pair(l(a.first, b.first, k.toDouble() / n), l(a.second, b.second, k.toDouble() / n))
    }
    return out
}

private fun circle(cx: Double, cy: Double, r: Double, n: Int = 16) = (0 until n).map { Pair(cx + r * cos(2 * PI * it / n), cy + r * sin(2 * PI * it / n)) }

private fun buildModel(pf: Profile, estate: Boolean): MeganeModel {
    val m = MeganeModel(pf, estate)
    val secs = pf.secs

    // ---------------------------------------------------------------- body loft
    val roofEnd = secs.first { it.edge - it.belt > 0.25 }.z
    fun topMat(zMid: Double): Mat = when {
        zMid > 0.95 -> Mat.BODY                                           // bonnet
        zMid > 0.0 -> Mat.GLASS                                           // windscreen
        zMid > roofEnd + 0.02 -> Mat.BODY                                 // roof
        pf.at(zMid).let { it.edge - it.belt > 0.09 } -> Mat.GLASS          // rear window
        else -> Mat.BODY
    }
    fun sideMat(zMid: Double, cabin: Boolean): Mat = when {
        !cabin -> Mat.BODY
        zMid > 0.80 -> Mat.BODY                                             // mirror sail / A-pillar base
        abs(zMid) < 0.06 -> Mat.TRIM                                        // black B-pillar
        abs(zMid + 1.0) < 0.06 -> Mat.TRIM                                  // C-pillar trim
        zMid < roofEnd + (if (estate) 0.16 else 0.30) -> Mat.BODY            // D-pillar
        else -> Mat.GLASS
    }
    for (i in 0 until secs.size - 1) {
        val a = secs[i]; val b = secs[i + 1]
        val ra = pf.ring(a); val rb = pf.ring(b)
        val zMid = (a.z + b.z) / 2
        val cabin = pf.at(zMid).let { it.edge - it.belt > 0.12 }
        for (sgn in listOf(1.0, -1.0)) {
            for (k in 0 until ra.size - 1) {
                val mat = when (k) {
                    0 -> Mat.TRIM                         // underbody
                    5, 6 -> sideMat(zMid, cabin)         // side glass band
                    8, 9 -> topMat(zMid)
                    else -> Mat.BODY
                }
                val q = listOf(V3(sgn * ra[k].x, ra[k].y, a.z), V3(sgn * rb[k].x, rb[k].y, b.z), V3(sgn * rb[k + 1].x, rb[k + 1].y, b.z), V3(sgn * ra[k + 1].x, ra[k + 1].y, a.z))
                face(q, mat, 1)?.let { m.body += it }
            }
        }
    }
    // end caps
    for (s in listOf(secs.first(), secs.last())) {
        val r = pf.ring(s)
        val pts = r.map { V3(it.x, it.y, s.z) } + r.reversed().drop(1).dropLast(1).map { V3(-it.x, it.y, s.z) }
        face(pts, Mat.BODY, 1)?.let { m.body += it }
    }

    // ---------------------------------------------------------------- decals
    fun dFront(poly: List<Pair<Double, Double>>, mat: Mat, off: Double = 0.006) =
        face(dense(poly).mapNotNull { (x, y) -> pf.front(x, y, off) }, mat, 2, V3(0.0, 0.5, 0.0))?.let { m.decals += it }
    fun dRear(poly: List<Pair<Double, Double>>, mat: Mat, off: Double = 0.006) =
        face(dense(poly).mapNotNull { (x, y) -> pf.rear(x, y, off) }, mat, 2, V3(0.0, 0.5, 0.0))?.let { m.decals += it }
    fun dSide(poly: List<Pair<Double, Double>>, mat: Mat, s: Double, off: Double = 0.006) =
        face(dense(poly).mapNotNull { (z, y) -> pf.side(z, y, s, off) }, mat, 2, V3(0.0, 0.6, poly.map { it.first }.average()))?.let { m.decals += it }
    fun dTop(poly: List<Pair<Double, Double>>, mat: Mat, off: Double = 0.006) =
        face(dense(poly).mapNotNull { (x, z) -> pf.top(x, z, off) }, mat, 2, V3(0.0, 0.0, poly.map { it.second }.average()))?.let { m.decals += it }

    for (s in listOf(1.0, -1.0)) {
        // headlights: swept back and up along the bonnet edge
        val hl = listOf(0.40 to 0.665, 0.43 to 0.742, 0.61 to 0.765, 0.765 to 0.782, 0.815 to 0.755, 0.805 to 0.70, 0.63 to 0.664).map { (x, y) -> s * x to y }
        val hcx = hl.map { it.first }.average(); val hcy = hl.map { it.second }.average()
        dFront(hl.map { (x, y) -> hcx + (x - hcx) * 1.05 to hcy + (y - hcy) * 1.10 }, Mat.GRILLE, 0.005)
        dFront(hl, Mat.HEAD, 0.007)
        dFront(circle(s * 0.71, 0.735, 0.032), Mat.CHROME, 0.009)
        dFront(circle(s * 0.575, 0.712, 0.024), Mat.CHROME, 0.009)
        // fog lights in the bumper corners
        dFront(circle(s * 0.66, 0.40, 0.062), Mat.GRILLE)
        dFront(circle(s * 0.66, 0.40, 0.040), Mat.LENS, 0.009)
        // tail lights wrapping round the corner
        dRear(listOf(0.47 to 0.845, 0.79 to 0.845, 0.815 to 0.925, 0.785 to 1.005, 0.56 to 0.995, 0.495 to 0.925).map { (x, y) -> s * x to y }, Mat.TAIL)
        dSide(listOf(-2.205 to 0.85, -2.075 to 0.865, -2.055 to 0.945, -2.12 to 1.0, -2.2 to 0.99), Mat.TAIL, s)
        dRear(listOf(0.60 to 0.452, 0.71 to 0.452, 0.71 to 0.472, 0.60 to 0.472).map { (x, y) -> s * x to y }, Mat.TAIL)
        // wheel-arch openings
        for (zc in listOf(1.35, -1.35)) {
            val arc = (0..18).map { val a = PI * it / 18; zc + 0.385 * cos(a) to 0.316 + 0.385 * sin(a) }
            val bottom = pf.at(zc).bottom + 0.004
            dSide(arc + listOf(zc - 0.385 to bottom, zc + 0.385 to bottom), Mat.CAVITY, s, 0.007)
        }
        // door seams (vertical) and the sill seam
        for (zs in listOf(0.92, -0.02, -1.02)) {
            val beltY = pf.at(zs).belt - 0.012
            dSide(listOf(zs to 0.31, zs + 0.007 to 0.31, zs + 0.007 to beltY, zs to beltY), Mat.TRIM, s)
        }
        dSide(listOf(-1.02 to 0.305, 0.92 to 0.305, 0.92 to 0.312, -1.02 to 0.312), Mat.TRIM, s)
        // black rubber line along the window sill
        dSide(listOf(-1.9 to 0.0, 0.84 to 0.0).let { listOf(-1.9 to pf.at(-1.9).belt - 0.014, 0.84 to pf.at(0.84).belt - 0.014, 0.84 to pf.at(0.84).belt - 0.002, -1.9 to pf.at(-1.9).belt - 0.002) }, Mat.TRIM, s)
        // rocker shading strip
        dSide(listOf(-0.96 to 0.19, 0.96 to 0.19, 0.96 to 0.235, -0.96 to 0.235), Mat.GRILLE, s, 0.004)
    }
    // front: grille band, lower intake, plate, lip
    dFront(listOf(-0.37 to 0.668, 0.37 to 0.668, 0.405 to 0.700, -0.405 to 0.700), Mat.GRILLE)
    dFront(listOf(-0.405 to 0.702, 0.405 to 0.702, 0.385 to 0.711, -0.385 to 0.711), Mat.CHROME)
    dFront(listOf(-0.52 to 0.31, 0.52 to 0.31, 0.58 to 0.52, -0.58 to 0.52), Mat.GRILLE)
    dFront(listOf(-0.50 to 0.355, 0.50 to 0.355, 0.51 to 0.365, -0.51 to 0.365), Mat.TRIM, 0.009)
    dFront(listOf(-0.26 to 0.415, 0.26 to 0.415, 0.26 to 0.525, -0.26 to 0.525), Mat.PLATE, 0.011)
    dFront(listOf(-0.26 to 0.415, -0.225 to 0.415, -0.225 to 0.525, -0.26 to 0.525), Mat.EUBLUE, 0.013)
    dFront(listOf(-0.60 to 0.272, 0.60 to 0.272, 0.60 to 0.30, -0.60 to 0.30), Mat.GRILLE)
    // rear: diffuser
    dRear(listOf(-0.62 to 0.335, 0.62 to 0.335, 0.66 to 0.42, -0.66 to 0.42), Mat.GRILLE)
    // fuel flap (right rear quarter): ring outline
    dSide(circle(-1.62, 0.80, 0.07), Mat.TRIM, 1.0)
    dSide(circle(-1.62, 0.80, 0.064), Mat.BODY, 1.0, 0.008)
    // bonnet seams + scuttle panel below the windscreen
    val hoodW = { z: Double -> pf.ringAt(z)[5].x - 0.075 }
    dTop(listOf(-hoodW(1.04) to 1.035, hoodW(1.04) to 1.035, hoodW(1.04) to 1.045, -hoodW(1.04) to 1.045), Mat.TRIM)
    for (s in listOf(1.0, -1.0)) {
        val line = (0..8).map { val z = l(1.04, 2.12, it / 8.0); s * hoodW(z) to z }
        dTop(line + line.reversed().map { (x, z) -> x - s * 0.007 to z }, Mat.TRIM)
    }
    dTop(listOf(-0.74 to 0.955, 0.74 to 0.955, 0.74 to 1.025, -0.74 to 1.025), Mat.GRILLE, 0.004)

    // ---------------------------------------------------------------- roof rails + antenna (layer 3)
    for (s in listOf(1.0, -1.0)) {
        val x = s * 0.585
        val zs = (0..10).map { l(roofEnd + 0.12, -0.12, it / 10.0) }
        val rail = zs.map { z -> V3(x, (pf.topY(z, 0.585) ?: 1.4) + 0.05, z) }
        for (i in 0 until rail.size - 1) {
            val a = rail[i]; val b = rail[i + 1]
            val w = 0.016; val h = 0.022
            fun box(p: V3) = listOf(V3(p.x - w, p.y, p.z), V3(p.x + w, p.y, p.z), V3(p.x + w, p.y + h, p.z), V3(p.x - w, p.y + h, p.z))
            val ba = box(a); val bb = box(b)
            for (k in 0 until 4) face(listOf(ba[k], bb[k], bb[(k + 1) % 4], ba[(k + 1) % 4]), Mat.CHROME, 3, V3(x, a.y + h / 2, (a.z + b.z) / 2))?.let { m.roof += it }
        }
        for (z in listOf(zs.first(), zs.last())) {   // feet
            val y0 = pf.topY(z, 0.585) ?: 1.4
            face(listOf(V3(x - 0.018, y0, z - 0.04), V3(x + 0.018, y0, z - 0.04), V3(x + 0.018, y0 + 0.06, z), V3(x - 0.018, y0 + 0.06, z)), Mat.TRIM, 3, V3(x, y0, z + 1))?.let { m.roof += it }
            face(listOf(V3(x - 0.018, y0, z + 0.04), V3(x + 0.018, y0, z + 0.04), V3(x + 0.018, y0 + 0.06, z), V3(x - 0.018, y0 + 0.06, z)), Mat.TRIM, 3, V3(x, y0, z - 1))?.let { m.roof += it }
        }
    }
    run { // shark-fin antenna
        val z0 = roofEnd + 0.22; val y0 = (pf.topY(z0, 0.0) ?: 1.4) + 0.004
        val tip = V3(0.0, y0 + 0.065, z0 - 0.05)
        val fl = V3(-0.03, y0, z0 + 0.12); val fr = V3(0.03, y0, z0 + 0.12); val bl = V3(-0.03, y0, z0 - 0.07); val br = V3(0.03, y0, z0 - 0.07)
        listOf(listOf(fl, bl, tip), listOf(br, fr, tip), listOf(fr, fl, tip), listOf(bl, br, tip)).forEach { face(it, Mat.GRILLE, 3, V3(0.0, y0, z0))?.let { f -> m.roof += f } }
    }

    // ---------------------------------------------------------------- mirrors (outer, per side)
    for (s in listOf(1, -1)) {
        val sx = s.toDouble()
        val x0 = sx * 0.885; val x1 = sx * 1.055
        val c = V3(sx * 0.97, 1.045, 0.84)
        val y0 = 0.985; val y1 = 1.11
        val zb = 0.775; val zf = 0.915; val zfo = 0.875   // front face tapers towards the outside
        val inner = listOf(V3(x0, y0, zb), V3(x0, y0, zf), V3(x0, y1, zf - 0.015), V3(x0, y1, zb))
        val outerF = listOf(V3(x1, y0 + 0.012, zb), V3(x1, y0 + 0.012, zfo), V3(x1, y1 - 0.01, zfo - 0.01), V3(x1, y1 - 0.01, zb))
        for (k in 0 until 4) face(listOf(inner[k], outerF[k], outerF[(k + 1) % 4], inner[(k + 1) % 4]), when (k) { 3 -> Mat.GLASS; else -> Mat.BODY }, 4, c)?.let { m.outer += s to it }
        face(outerF, Mat.BODY, 4, c)?.let { m.outer += s to it }
        // glass on the rear face
        face(listOf(V3(x0 + sx * 0.012, y0 + 0.012, zb - 0.002), V3(x1 - sx * 0.008, y0 + 0.022, zb - 0.002), V3(x1 - sx * 0.008, y1 - 0.02, zb - 0.002), V3(x0 + sx * 0.012, y1 - 0.012, zb - 0.002)), Mat.GLASS, 4, c)?.let { m.outer += s to it }
        // indicator repeater strip on the mirror
        face(listOf(V3(x1 + sx * 0.002, y0 + 0.03, zb + 0.01), V3(x1 + sx * 0.002, y0 + 0.03, zfo - 0.01), V3(x1 + sx * 0.002, y0 + 0.045, zfo - 0.012), V3(x1 + sx * 0.002, y0 + 0.045, zb + 0.01)), Mat.LENS, 4, c)?.let { m.outer += s to it }
    }

    // ---------------------------------------------------------------- wheels (outer, per side)
    val r = 0.316; val rRim = 0.205; val width = 0.205; val track = 0.775; val n = 24
    for (zc in listOf(1.35, -1.35)) for (s in listOf(1, -1)) {
        val sx = s.toDouble()
        val group = (if (zc > 0) 1 else 3) + (if (s > 0) 0 else 1)
        val wheelStart = m.outer.size
        val archR = 0.385
        m.arches[group] = (0..18).mapNotNull { val a = PI * it / 18; pf.side(zc + archR * cos(a), 0.316 + archR * sin(a), sx, 0.0) } +
            listOf(V3(sx * pf.at(zc - archR).hw, -0.06, zc - archR), V3(sx * pf.at(zc + archR).hw, -0.06, zc + archR))
        val xo = sx * (track + width / 2); val xi = sx * (track - width / 2)
        val c = V3(sx * track, r, zc)
        fun at(x: Double, rad: Double, a: Double) = V3(x, r + rad * sin(a), zc + rad * cos(a))
        val ang = (0 until n).map { 2 * PI * it / n }
        for (k in 0 until n) {
            val a = ang[k]; val b = ang[(k + 1) % n]
            face(listOf(at(xo - sx * 0.012, r, a), at(xo - sx * 0.012, r, b), at(xi, r, b), at(xi, r, a)), Mat.TIRE, 4, c)?.let { m.outer += s to it }
            // outer sidewall: two bands, slightly bulged
            face(listOf(at(xo - sx * 0.012, r, a), at(xo - sx * 0.012, r, b), at(xo + sx * 0.004, 0.27, b), at(xo + sx * 0.004, 0.27, a)), Mat.TIRE, 4, c.copy(x = 0.0))?.let { m.outer += s to it }
            face(listOf(at(xo + sx * 0.004, 0.27, a), at(xo + sx * 0.004, 0.27, b), at(xo - sx * 0.004, 0.214, b), at(xo - sx * 0.004, 0.214, a)), Mat.TIRE, 4, c.copy(x = 0.0))?.let { m.outer += s to it }
        }
        face(ang.map { at(xi, r * 0.98, it) }, Mat.TIRE, 4, c.copy(x = sx * 2))?.let { m.outer += s to it }   // inner side
        // 5-spoke alloy
        val xr = xo - sx * 0.012
        face(ang.map { at(xr, rRim, it) }, Mat.RIM, 4, c.copy(x = 0.0))?.let { m.outer += s to it }
        for (k in 0 until 5) {
            val a0 = 2 * PI * k / 5 + PI / 2
            val outerArc = (0..5).map { at(xr + sx * 0.002, 0.182, a0 + Math.toRadians(17.0) + Math.toRadians(38.0) * it / 5) }
            val innerArc = (0..3).map { at(xr + sx * 0.002, 0.075, a0 + Math.toRadians(24.0) + Math.toRadians(24.0) * (3 - it) / 3) }
            face(outerArc + innerArc, Mat.CAVITY, 4, c.copy(x = 0.0))?.let { m.outer += s to it }
        }
        face((0 until 12).map { at(xr + sx * 0.004, 0.052, 2 * PI * it / 12) }, Mat.CHROME, 4, c.copy(x = 0.0))?.let { m.outer += s to it }
        face((0 until 5).map { at(xr + sx * 0.006, 0.016, 2 * PI * it / 5 + PI / 2) }, Mat.GRILLE, 4, c.copy(x = 0.0))?.let { m.outer += s to it }
        for (i in wheelStart until m.outer.size) m.outer[i] = m.outer[i].first to m.outer[i].second.let { Face(it.pts, it.mat, it.layer, it.doubleSided, it.open, group) }
    }

    // ---------------------------------------------------------------- doors (templates, closed)
    fun door(zFront: Double, zRear: Double, s: Double, handle: Pair<Double, Double>): MeganeModel.PartTpl {
        val faces = ArrayList<Face>(); val ap = ArrayList<Face>()
        val zs = (0..6).map { l(zFront, zRear, it / 6.0) }
        for (i in 0 until zs.size - 1) {
            val za = zs[i]; val zb = zs[i + 1]
            val ys = { z: Double -> (0..4).map { l(0.312, pf.at(z).belt, it / 4.0) } }
            val ya = ys(za); val yb = ys(zb)
            for (k in 0 until 4) {
                val q = listOf(pf.side(za, ya[k], s, 0.011), pf.side(zb, yb[k], s, 0.011), pf.side(zb, yb[k + 1], s, 0.011), pf.side(za, ya[k + 1], s, 0.011))
                if (q.all { it != null }) {
                    face(q.map { it!! }, Mat.BODY, 2, V3(0.0, 0.6, (za + zb) / 2))?.let { faces += it }
                    face(q.map { it!!.copy(x = it.x - s * 0.003) }, Mat.CAVITY, 2, V3(0.0, 0.6, (za + zb) / 2))?.let { ap += it }
                }
            }
            // window (follows the tumblehome), framed like the body side band
            val ra = pf.ringAt(za); val rb = pf.ringAt(zb)
            val zMid = (za + zb) / 2
            val cabin = pf.at(zMid).let { it.edge - it.belt > 0.12 }
            for (k in 5..6) {
                val q = listOf(V3(s * (ra[k].x + 0.011), ra[k].y, za), V3(s * (rb[k].x + 0.011), rb[k].y, zb), V3(s * (rb[k + 1].x + 0.011), rb[k + 1].y, zb), V3(s * (ra[k + 1].x + 0.011), ra[k + 1].y, za))
                face(q, sideMat(zMid, cabin), 2, V3(0.0, 0.8, zMid))?.let { faces += it }
                face(q.map { it.copy(x = it.x - s * 0.003) }, Mat.CAVITY, 2, V3(0.0, 0.8, zMid))?.let { ap += it }
            }
        }
        val details = listOfNotNull(face(dense(listOf(handle.first to 0.862, handle.second to 0.862, handle.second to 0.888, handle.first to 0.888)).mapNotNull { (z, y) -> pf.side(z, y, s, 0.015) }, Mat.TRIM, 2, V3(0.0, 0.6, handle.first)))
        val hinge = V3(s * (pf.halfWidth(zFront, 0.6) ?: 0.89), 0.6, zFront)
        return MeganeModel.PartTpl(faces, ap, hinge, details)
    }
    m.frontDoor = MeganeModel.DoorTpl(door(0.92, -0.02, -1.0, 0.28 to 0.13), door(0.92, -0.02, 1.0, 0.28 to 0.13))
    m.rearDoor = MeganeModel.DoorTpl(door(-0.02, -1.02, -1.0, -0.72 to -0.87), door(-0.02, -1.02, 1.0, -0.72 to -0.87))

    // ---------------------------------------------------------------- bonnet
    run {
        val faces = ArrayList<Face>(); val ap = ArrayList<Face>()
        val zs = (0..7).map { l(1.045, 2.13, it / 7.0) }
        for (i in 0 until zs.size - 1) for (j in 0 until 8) {
            val pts = listOf(i to j, i + 1 to j, i + 1 to j + 1, i to j + 1).map { (a, b) ->
                val z = zs[a]; val w = hoodW(z); val x = l(-w, w, b / 8.0)
                pf.top(x, z, 0.011)
            }
            if (pts.all { it != null }) {
                face(pts.map { it!! }, Mat.BODY, 2, V3(0.0, 0.0, (zs[i] + zs[i + 1]) / 2))?.let { faces += it }
                face(pts.map { it!!.copy(y = it.y - 0.004) }, Mat.CAVITY, 2, V3(0.0, 0.0, (zs[i] + zs[i + 1]) / 2))?.let { ap += it }
            }
        }
        m.hood = MeganeModel.PartTpl(faces, ap, V3(0.0, pf.topY(1.045, 0.0) ?: 1.0, 1.045))
    }

    // ---------------------------------------------------------------- tailgate (with plate, handle, 3rd brake light)
    run {
        val faces = ArrayList<Face>(); val ap = ArrayList<Face>()
        val yTop = (pf.at(roofEnd).crown - 0.02).coerceAtMost(1.38)
        val glassFrom = if (estate) 1.06 else 0.95
        val xs = (0..8).map { l(-0.62, 0.62, it / 8.0) }
        val ys = (0..8).map { l(0.60, yTop, it / 8.0) }
        for (i in 0 until xs.size - 1) for (j in 0 until ys.size - 1) {
            val pts = listOf(xs[i] to ys[j], xs[i + 1] to ys[j], xs[i + 1] to ys[j + 1], xs[i] to ys[j + 1]).map { (x, y) -> pf.rear(x, y, 0.011) }
            if (pts.any { it == null }) continue
            val cy = (ys[j] + ys[j + 1]) / 2; val cx = abs((xs[i] + xs[i + 1]) / 2)
            val mat = if (cy > glassFrom && cy < yTop - 0.04 && cx < 0.55) Mat.GLASS else Mat.BODY
            face(pts.map { it!! }, mat, 2, V3(0.0, 0.6, 0.0))?.let { faces += it }
            face(pts.map { it!!.copy(z = it.z + 0.004) }, Mat.CAVITY, 2, V3(0.0, 0.6, 0.0))?.let { ap += it }
        }
        val details = ArrayList<Face>()
        fun onGate(poly: List<Pair<Double, Double>>, mat: Mat, off: Double) =
            face(dense(poly).mapNotNull { (x, y) -> pf.rear(x, y, off) }, mat, 2, V3(0.0, 0.6, 0.0))?.let { details += it }
        onGate(listOf(-0.26 to 0.62, 0.26 to 0.62, 0.26 to 0.73, -0.26 to 0.73), Mat.PLATE, 0.016)
        onGate(listOf(-0.26 to 0.62, -0.225 to 0.62, -0.225 to 0.73, -0.26 to 0.73), Mat.EUBLUE, 0.018)
        onGate(listOf(-0.30 to 0.745, 0.30 to 0.745, 0.30 to 0.765, -0.30 to 0.765), Mat.CHROME, 0.016)
        onGate(listOf(-0.22 to yTop - 0.03, 0.22 to yTop - 0.03, 0.22 to yTop - 0.012, -0.22 to yTop - 0.012), Mat.TAIL, 0.016)
        m.tailgate = MeganeModel.PartTpl(faces, ap, V3(0.0, yTop, pf.rearZ(0.0, yTop) ?: roofEnd), details)
    }
    return m
}

/** Wheel-arch outlines (group id → 3D outline) for clipping near wheels. */
internal fun meganeArches(bodyType: String): Map<Int, List<V3>> = meganeModel(bodyType).arches

// ============================================================ per-frame assembly

private fun rotY(p: V3, pivot: V3, a: Double): V3 { val d = p - pivot; return pivot + V3(d.x * cos(a) + d.z * sin(a), d.y, -d.x * sin(a) + d.z * cos(a)) }
private fun rotX(p: V3, pivot: V3, a: Double): V3 { val d = p - pivot; return pivot + V3(d.x, d.y * cos(a) - d.z * sin(a), d.y * sin(a) + d.z * cos(a)) }

private fun Face.moved(f: (V3) -> V3, layer: Int, open: Float, bias: Double = 0.0) = Face(pts.map(f), mat, layer, open > 0.02f, open, 0, bias)

/**
 * Faces for one frame. Layers: 0 far wheels/mirror · 1 body + far moving parts (sorted) ·
 * 2 decals + closed moving parts (in order) · 3 roof parts + near moving parts (sorted) ·
 * 4 near wheels/mirror (sorted).
 */
internal fun buildMegane(bodyType: String, pose: CarPose, nearRight: Boolean, camBehind: Boolean): List<Face> {
    val m = meganeModel(bodyType)
    val out = ArrayList<Face>(m.body.size + m.decals.size + 600)
    out += m.body
    out += m.decals
    out += m.roof
    val nearSide = if (nearRight) 1 else -1
    for ((s, f) in m.outer) out += Face(f.pts, f.mat, if (s == nearSide) 4 else 0, f.doubleSided, f.open, f.group)

    fun part(tpl: MeganeModel.PartTpl, open: Float, near: Boolean, transform: (V3, Double) -> V3) {
        // closed: the panel is identical to the body underneath, so only its details are drawn
        if (open <= 0.02f) { out += tpl.details; return }
        out += tpl.aperture                                            // dark opening behind it (layer 2)
        val layer = if (near) 3 else 1
        tpl.faces.forEach { f -> out += f.moved({ transform(it, open.toDouble()) }, layer, open) }
        tpl.details.forEach { f -> out += f.moved({ transform(it, open.toDouble()) }, layer, open, bias = 0.05) }
    }
    fun doorRot(tpl: MeganeModel.PartTpl, s: Double) = { p: V3, o: Double -> rotY(p, tpl.hinge, Math.toRadians(68.0 * o) * (if (s > 0) -1 else 1)) }
    part(m.frontDoor.left, pose.doorFL, nearSide == -1, doorRot(m.frontDoor.left, -1.0))
    part(m.frontDoor.right, pose.doorFR, nearSide == 1, doorRot(m.frontDoor.right, 1.0))
    part(m.rearDoor.left, pose.doorRL, nearSide == -1, doorRot(m.rearDoor.left, -1.0))
    part(m.rearDoor.right, pose.doorRR, nearSide == 1, doorRot(m.rearDoor.right, 1.0))
    part(m.hood, pose.hood, !camBehind) { p, o -> rotX(p, m.hood.hinge, -Math.toRadians(52.0 * o)) }
    part(m.tailgate, pose.trunk, camBehind) { p, o -> rotX(p, m.tailgate.hinge, Math.toRadians(95.0 * o)) }
    return out
}
