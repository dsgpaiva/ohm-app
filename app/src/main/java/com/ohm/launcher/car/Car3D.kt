package com.ohm.launcher.car

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * A small software 3D renderer for a low-poly hatchback (Mégane-like proportions).
 * No GPU engine or model file needed, so it runs on any head unit. It supports orbiting,
 * zoom, opening doors/trunk/hood, headlights and brake lights (SPEC.md §5.1).
 * A real glTF model via Filament can replace it later without touching the rest of the UI.
 */

data class V3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = V3(x * s, y * s, z * s)
    fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun norm(): V3 { val l = sqrt(dot(this)); return if (l == 0.0) this else V3(x / l, y / l, z / l) }
}

enum class Mat { BODY, GLASS, TIRE, RIM, HEAD, TAIL, GRILLE, TRIM, CHROME, PLATE, EUBLUE, CAVITY, LENS }

/** Layer: 0 far outer parts · 1 body (sorted) · 2 decals (in order) · 3 roof/near moving parts (sorted) · 4 near outer parts (sorted). */
class Face(val pts: List<V3>, val mat: Mat, val layer: Int, val doubleSided: Boolean = false, val open: Float = 0f, val group: Int = 0,
           /** drawn slightly in front of faces at the same depth (details on moving panels) */
           val bias: Double = 0.0)

/** Arch outlines for the detailed model (empty for the simple one). */
fun archOutlines(body: String, detailed: Boolean): Map<Int, List<V3>> = if (detailed) meganeArches(body) else emptyMap()

val CarPaints = listOf(
    "Glacier White" to Color(0xFFE9EAEC),
    "Midnight Black" to Color(0xFF1E1F22),
    "Volcano Grey" to Color(0xFF6B6E73),
    "Deep Blue" to Color(0xFF1F3F8F),
    "Flame Red" to Color(0xFFB3261E),
    "Silver" to Color(0xFFB9BCC1),
)

private class Station(val z: Double, val hw: Double, val bot: Double, val belt: Double, val top: Double, val rw: Double) {
    val cabin get() = top > belt + 0.03
}

private fun stations(body: String): List<Station> = when (body) {
    "estate" -> listOf(
        Station(-2.42, 0.78, 0.32, 0.72, 0.72, 0.70), Station(-2.35, 0.86, 0.22, 0.92, 0.92, 0.80),
        Station(-2.25, 0.88, 0.20, 0.96, 1.30, 0.66), Station(-2.05, 0.89, 0.20, 0.97, 1.46, 0.64),
        Station(-1.35, 0.90, 0.20, 0.98, 1.49, 0.66), Station(-0.30, 0.90, 0.20, 0.97, 1.49, 0.68),
        Station(0.30, 0.90, 0.20, 0.96, 1.42, 0.67), Station(0.95, 0.89, 0.20, 0.93, 0.98, 0.78),
        Station(1.60, 0.86, 0.22, 0.85, 0.85, 0.74), Station(2.05, 0.80, 0.26, 0.74, 0.74, 0.66),
        Station(2.18, 0.70, 0.34, 0.62, 0.62, 0.58),
    )
    "coupe" -> listOf(
        Station(-2.15, 0.78, 0.32, 0.72, 0.72, 0.70), Station(-2.08, 0.87, 0.22, 0.92, 0.92, 0.80),
        Station(-1.95, 0.89, 0.20, 0.95, 1.00, 0.66), Station(-1.55, 0.90, 0.20, 0.96, 1.22, 0.62),
        Station(-0.95, 0.91, 0.20, 0.96, 1.38, 0.64), Station(-0.20, 0.91, 0.20, 0.95, 1.40, 0.66),
        Station(0.35, 0.90, 0.20, 0.94, 1.33, 0.66), Station(1.00, 0.89, 0.20, 0.91, 0.95, 0.78),
        Station(1.60, 0.86, 0.22, 0.83, 0.83, 0.74), Station(2.05, 0.80, 0.26, 0.72, 0.72, 0.66),
        Station(2.18, 0.70, 0.34, 0.60, 0.60, 0.58),
    )
    else -> listOf( // hatch
        Station(-2.15, 0.78, 0.32, 0.72, 0.72, 0.70), Station(-2.08, 0.86, 0.22, 0.92, 0.92, 0.80),
        Station(-1.95, 0.88, 0.20, 0.96, 1.05, 0.66), Station(-1.70, 0.89, 0.20, 0.97, 1.38, 0.64),
        Station(-1.35, 0.90, 0.20, 0.98, 1.47, 0.66), Station(-0.30, 0.90, 0.20, 0.97, 1.48, 0.68),
        Station(0.30, 0.90, 0.20, 0.96, 1.42, 0.67), Station(0.95, 0.89, 0.20, 0.93, 0.98, 0.78),
        Station(1.60, 0.86, 0.22, 0.85, 0.85, 0.74), Station(2.05, 0.80, 0.26, 0.74, 0.74, 0.66),
        Station(2.18, 0.70, 0.34, 0.62, 0.62, 0.58),
    )
}

private val CENTER = V3(0.0, 0.7, 0.0)

/** Orient the polygon so its normal points away from [ref]. */
private fun outward(pts: List<V3>, ref: V3): List<V3> {
    val n = (pts[1] - pts[0]).cross(pts[2] - pts[0])
    val c = pts.fold(V3(0.0, 0.0, 0.0)) { a, b -> a + b } * (1.0 / pts.size)
    return if (n.dot(c - ref) < 0) pts.reversed() else pts
}

private fun rotY(p: V3, pivot: V3, a: Double): V3 {
    val d = p - pivot
    return pivot + V3(d.x * cos(a) + d.z * sin(a), d.y, -d.x * sin(a) + d.z * cos(a))
}

private fun rotX(p: V3, pivot: V3, a: Double): V3 {
    val d = p - pivot
    return pivot + V3(d.x, d.y * cos(a) - d.z * sin(a), d.y * sin(a) + d.z * cos(a))
}

/** Interpolates the body profile at an arbitrary z. */
private fun profileAt(st: List<Station>, z: Double): Station {
    val i = st.indexOfLast { it.z <= z }.coerceIn(0, st.size - 2)
    val a = st[i]; val b = st[i + 1]
    val t = ((z - a.z) / (b.z - a.z)).coerceIn(0.0, 1.0)
    fun l(x: Double, y: Double) = x + (y - x) * t
    return Station(z, l(a.hw, b.hw), l(a.bot, b.bot), l(a.belt, b.belt), l(a.top, b.top), l(a.rw, b.rw))
}

data class CarPose(
    val doorFL: Float = 0f, val doorFR: Float = 0f, val doorRL: Float = 0f, val doorRR: Float = 0f,
    val trunk: Float = 0f, val hood: Float = 0f,
)

/** Detailed Mégane III (default) or the simple low-poly car used in low-power mode. */
fun buildCar(body: String, pose: CarPose, nearSideRight: Boolean, camBehind: Boolean = true, detailed: Boolean = true): List<Face> =
    if (detailed) buildMegane(body, pose, nearSideRight, camBehind) else buildSimpleCar(body, pose, nearSideRight)

fun buildSimpleCar(body: String, pose: CarPose, nearSideRight: Boolean): List<Face> {
    val st = stations(body)
    val out = ArrayList<Face>(260)
    fun body(pts: List<V3>, mat: Mat = Mat.BODY) = out.add(Face(outward(pts, CENTER), mat, 1))

    // ---- body loft
    for (i in 0 until st.size - 1) {
        val a = st[i]; val b = st[i + 1]
        for (s in listOf(1.0, -1.0)) {
            val a0 = V3(s * a.hw, a.bot, a.z); val a1 = V3(s * a.hw, a.belt, a.z); val a2 = V3(s * a.rw, a.top, a.z)
            val b0 = V3(s * b.hw, b.bot, b.z); val b1 = V3(s * b.hw, b.belt, b.z); val b2 = V3(s * b.rw, b.top, b.z)
            body(listOf(a0, b0, b1, a1))
            body(listOf(a1, b1, b2, a2), if (a.cabin && b.cabin) Mat.GLASS else Mat.BODY)
        }
        val slope = kotlin.math.abs((b.top - a.top) / (b.z - a.z))
        val glassTop = a.cabin && b.cabin && slope > 0.5
        body(listOf(V3(a.rw, a.top, a.z), V3(b.rw, b.top, b.z), V3(-b.rw, b.top, b.z), V3(-a.rw, a.top, a.z)), if (glassTop) Mat.GLASS else Mat.BODY)
        // underside (dark) so the car looks solid from low angles
        body(listOf(V3(a.hw, a.bot, a.z), V3(-a.hw, a.bot, a.z), V3(-b.hw, b.bot, b.z), V3(b.hw, b.bot, b.z)), Mat.TRIM)
    }
    for (s in listOf(st.first(), st.last())) {
        body(listOf(V3(s.hw, s.bot, s.z), V3(s.hw, s.belt, s.z), V3(s.rw, s.top, s.z), V3(-s.rw, s.top, s.z), V3(-s.hw, s.belt, s.z), V3(-s.hw, s.bot, s.z)))
    }

    // ---- decals: lights, grille
    val f = st.last(); val r = st.first()
    val fz = f.z + 0.006; val rz = r.z - 0.006
    for (s in listOf(1.0, -1.0)) {
        out.add(Face(outward(listOf(V3(s * 0.34, f.belt - 0.12, fz), V3(s * 0.64, f.belt - 0.10, fz), V3(s * 0.64, f.belt - 0.01, fz), V3(s * 0.34, f.belt - 0.03, fz)), V3(0.0, 0.5, 0.0)), Mat.HEAD, 2))
        out.add(Face(outward(listOf(V3(s * 0.46, r.belt - 0.15, rz), V3(s * 0.74, r.belt - 0.15, rz), V3(s * 0.74, r.belt - 0.03, rz), V3(s * 0.46, r.belt - 0.05, rz)), V3(0.0, 0.5, 0.0)), Mat.TAIL, 2))
    }
    out.add(Face(outward(listOf(V3(0.42, 0.38, fz), V3(-0.42, 0.38, fz), V3(-0.42, 0.50, fz), V3(0.42, 0.50, fz)), V3(0.0, 0.5, 0.0)), Mat.GRILLE, 2))

    // ---- doors (hinged at the front edge, open outwards)
    fun door(side: Double, zFront: Double, zRear: Double, angle: Float) {
        val pf = profileAt(st, zFront); val pr = profileAt(st, zRear)
        val off = 0.008
        val lower = listOf(
            V3(side * (pr.hw + off), pr.bot + 0.10, zRear), V3(side * (pf.hw + off), pf.bot + 0.10, zFront),
            V3(side * (pf.hw + off), pf.belt, zFront), V3(side * (pr.hw + off), pr.belt, zRear),
        )
        val upper = listOf(
            V3(side * (pr.hw + off), pr.belt, zRear), V3(side * (pf.hw + off), pf.belt, zFront),
            V3(side * (pf.rw + 0.02 + off), pf.top - 0.05, zFront), V3(side * (pr.rw + 0.02 + off), pr.top - 0.05, zRear),
        )
        val a = Math.toRadians(65.0 * angle) * (if (side > 0) -1 else 1)
        val pivot = V3(side * pf.hw, 0.0, zFront)
        val open = angle > 0.02
        val ref = V3(0.0, 0.7, (zFront + zRear) / 2)
        out.add(Face(outward(lower, ref).map { rotY(it, pivot, a) }, Mat.BODY, if (open) 1 else 2, open, angle))
        out.add(Face(outward(upper, ref).map { rotY(it, pivot, a) }, Mat.GLASS, if (open) 1 else 2, open, angle))
        // thin dark seam so closed doors are visible as panels
        out.add(Face(outward(listOf(V3(side * (pr.hw + off * 1.5), pr.bot + 0.10, zRear + 0.012), V3(side * (pr.hw + off * 1.5), pr.bot + 0.10, zRear - 0.004),
            V3(side * (pr.hw + off * 1.5), pr.belt, zRear - 0.004), V3(side * (pr.hw + off * 1.5), pr.belt, zRear + 0.012)), ref).map { rotY(it, pivot, a) }, Mat.TRIM, 2))
    }
    door(1.0, 0.92, -0.05, pose.doorFR)
    door(-1.0, 0.92, -0.05, pose.doorFL)
    door(1.0, -0.05, -1.05, pose.doorRR)
    door(-1.0, -0.05, -1.05, pose.doorRL)

    // ---- hood (hinged at windshield base, opens upwards)
    run {
        val a = st[7]; val b = st[st.size - 2]
        val pts = listOf(V3(a.rw - 0.04, a.top + 0.006, a.z + 0.02), V3(-a.rw + 0.04, a.top + 0.006, a.z + 0.02),
            V3(-b.rw + 0.02, b.top + 0.006, b.z), V3(b.rw - 0.02, b.top + 0.006, b.z))
        val pivot = V3(0.0, a.top, a.z)
        val ang = -Math.toRadians(55.0 * pose.hood)
        out.add(Face(outward(pts, V3(0.0, 0.0, 1.0)).map { rotX(it, pivot, ang) }, Mat.BODY, if (pose.hood > 0.02) 1 else 2, pose.hood > 0.02, pose.hood))
    }

    // ---- tailgate (hinged at the roof's rear edge, opens upwards)
    run {
        val roofRear = st[3]; val base = st[1]
        val pts = listOf(V3(roofRear.rw - 0.02, roofRear.top + 0.006, roofRear.z), V3(-roofRear.rw + 0.02, roofRear.top + 0.006, roofRear.z),
            V3(-base.rw, base.top + 0.006, base.z - 0.006), V3(base.rw, base.top + 0.006, base.z - 0.006))
        val pivot = V3(0.0, roofRear.top, roofRear.z)
        val ang = Math.toRadians(70.0 * pose.trunk)
        out.add(Face(outward(pts, V3(0.0, 0.5, 0.0)).map { rotX(it, pivot, ang) }, Mat.GLASS, if (pose.trunk > 0.02) 1 else 2, pose.trunk > 0.02, pose.trunk))
    }

    // ---- wheels
    val wr = 0.31; val ww = 0.22; val segs = 12
    for (zc in listOf(1.32, -1.30)) for (side in listOf(1.0, -1.0)) {
        val near = (side > 0) == nearSideRight
        val layer = if (near) 3 else 0
        val xo = side * (0.94); val xi = side * (0.94 - ww)
        val c = V3((xo + xi) / 2, wr, zc)
        val ring = (0 until segs).map { k -> val t = 2 * Math.PI * k / segs; Pair(wr * cos(t), wr * sin(t)) }
        for (k in 0 until segs) {
            val (y1, z1) = ring[k]; val (y2, z2) = ring[(k + 1) % segs]
            out.add(Face(outward(listOf(V3(xo, wr + y1, zc + z1), V3(xo, wr + y2, zc + z2), V3(xi, wr + y2, zc + z2), V3(xi, wr + y1, zc + z1)), c), Mat.TIRE, layer))
        }
        out.add(Face(outward(ring.map { (y, z) -> V3(xo, wr + y, zc + z) }, c), Mat.TIRE, layer))
        out.add(Face(outward(ring.map { (y, z) -> V3(xo + side * 0.004, wr + y * 0.68, zc + z * 0.68) }, c), Mat.RIM, layer))
    }
    return out
}

private data class Mat5(val base: Color, val amb: Double, val kd: Double, val ks: Double, val sh: Double)

private class Projected(val path: Path, val depth: Double, val color: Color, val center: Offset, val mat: Mat, val group: Int = 0)

/**
 * Orbitable car view. Drag to rotate, pinch to zoom, double-tap to reset.
 */
@Composable
fun Car3DView(
    modifier: Modifier,
    state: CarState,
    bodyType: String,
    paint: Color,
    dark: Boolean,
    lowPower: Boolean,
) {
    var yaw by remember { mutableFloatStateOf(-38f) }
    var pitch by remember { mutableFloatStateOf(24f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    val sp: androidx.compose.animation.core.AnimationSpec<Float> =
        if (com.ohm.launcher.ui.theme.Motion.off) androidx.compose.animation.core.snap()
        else spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow)
    val d = state.doors
    val pose = CarPose(
        doorFL = animateFloatAsState(if (d.fl) 1f else 0f, sp, label = "fl").value,
        doorFR = animateFloatAsState(if (d.fr) 1f else 0f, sp, label = "fr").value,
        doorRL = animateFloatAsState(if (d.rl) 1f else 0f, sp, label = "rl").value,
        doorRR = animateFloatAsState(if (d.rr) 1f else 0f, sp, label = "rr").value,
        trunk = animateFloatAsState(if (d.trunk) 1f else 0f, sp, label = "trunk").value,
        hood = animateFloatAsState(if (d.hood) 1f else 0f, sp, label = "hood").value,
    )
    // When something opens, swing the camera round to show it (left side, right side, rear, front).
    var lastDoors by remember { mutableStateOf(d) }
    LaunchedEffect(d) {
        val prev = lastDoors; lastDoors = d
        val target = when {
            d.trunk && !prev.trunk -> 0f
            d.hood && !prev.hood -> 180f
            (d.fl && !prev.fl) || (d.rl && !prev.rl) -> 32f
            (d.fr && !prev.fr) || (d.rr && !prev.rr) -> -32f
            else -> null
        } ?: return@LaunchedEffect
        // shortest way round
        var delta = (target - yaw) % 360f; if (delta > 180f) delta -= 360f; if (delta < -180f) delta += 360f
        androidx.compose.animation.core.animate(yaw, yaw + delta, animationSpec = androidx.compose.animation.core.tween(com.ohm.launcher.ui.theme.Motion.dur(700))) { v, _ -> yaw = v }
    }
    val lights = animateFloatAsState(if (state.headlights == true) 1f else 0f, label = "lights").value
    val brake = animateFloatAsState(if (state.braking || state.gear == Gear.R) 1f else 0f, label = "brake").value

    Canvas(
        modifier
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, z, _ ->
                    yaw += pan.x * 0.35f
                    pitch = (pitch + pan.y * 0.15f).coerceIn(4f, 60f)
                    zoom = (zoom * z).coerceIn(0.7f, 1.6f)
                }
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { yaw = -38f; pitch = 24f; zoom = 1f }) }
    ) {
        drawCar(this, bodyType, pose, yaw.toDouble(), pitch.toDouble(), zoom.toDouble(), paint, dark, lowPower, lights, brake)
    }
}

private fun drawCar(
    ds: DrawScope, bodyType: String, pose: CarPose, yawDeg: Double, pitchDeg: Double, zoom: Double,
    paint: Color, dark: Boolean, lowPower: Boolean, lights: Float, brake: Float,
) = with(ds) {
    val yaw = Math.toRadians(yawDeg); val pitch = Math.toRadians(pitchDeg)
    val dist = 9.6 / zoom
    val f = min(size.width * 1.55, size.height * 2.6)
    val cx = size.width / 2.0; val cy = size.height * 0.56
    val cy0 = cos(yaw); val sy0 = sin(yaw); val cp = cos(pitch); val spch = sin(pitch)

    // camera x position in car space decides which wheels are on the near side
    val camX = -sin(yaw)
    val nearRight = camX > 0

    fun view(p: V3): V3 {
        val x = p.x; val y = p.y - 0.55; val z = p.z
        val x1 = x * cy0 - z * sy0
        val z1 = x * sy0 + z * cy0
        val y2 = y * cp + z1 * spch
        val z2 = z1 * cp - y * spch + dist
        return V3(x1, y2, z2)
    }
    fun scr(v: V3) = Offset((cx + v.x * f / v.z).toFloat(), (cy - v.y * f / v.z).toFloat())

    // ---- ground shadow
    run {
        val path = Path()
        for (k in 0..32) {
            val t = 2 * Math.PI * k / 32
            val o = scr(view(V3(1.15 * cos(t), 0.0, 2.55 * sin(t))))
            if (k == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        path.close()
        val c = scr(view(V3(0.0, 0.0, 0.0)))
        val rad = (f / dist * 2.6).toFloat()
        if (lowPower) drawPath(path, Color.Black.copy(alpha = if (dark) 0.45f else 0.18f))
        else drawPath(path, Brush.radialGradient(listOf(Color.Black.copy(alpha = if (dark) 0.6f else 0.28f), Color.Transparent), c, rad))
    }

    val camBehind = -cos(yaw) < 0
    val faces = buildCar(bodyType, pose, nearRight, camBehind, detailed = !lowPower)
    val headOn = lerp(Color(0xFF6C747C), Color(0xFFFFF6DC), lights)
    val tail = lerp(Color(0xFF7A1611), Color(0xFFFF2A1A), max(lights * 0.55f, brake))
    val glass = if (dark) Color(0xFF0E1216) else Color(0xFF1C232B)
    val buckets = Array(5) { ArrayList<Projected>() }
    // Studio lighting in view space (key from upper left, soft fill from the right).
    val key = V3(-0.45, 0.75, -0.48).norm()
    val fill = V3(0.65, 0.15, -0.45).norm()

    for (fc in faces) {
        val v = fc.pts.map { view(it) }
        if (v.any { it.z < 0.5 }) continue
        // Newell normal: robust for many-point, slightly non-planar decals
        var nx = 0.0; var ny = 0.0; var nz = 0.0
        for (k in v.indices) {
            val p0 = v[k]; val p1 = v[(k + 1) % v.size]
            nx += (p0.y - p1.y) * (p0.z + p1.z); ny += (p0.z - p1.z) * (p0.x + p1.x); nz += (p0.x - p1.x) * (p0.y + p1.y)
        }
        var n = V3(nx, ny, nz).norm()
        if (n.dot(n) < 0.5) continue
        val ctr = V3(v.sumOf { it.x } / v.size, v.sumOf { it.y } / v.size, v.sumOf { it.z } / v.size)
        val facing = n.dot(ctr) < 0
        if (!facing && !fc.doubleSided) continue
        if (!facing) n = n * -1.0
        val toEye = (ctr * -1.0).norm()
        // flat details seen almost edge-on would paint over geometry in front of them
        if (fc.layer == 2 && facing && n.dot(toEye) < 0.12) continue
        val diff = max(0.0, n.dot(key)); val fil = max(0.0, n.dot(fill))
        val hv = (key + toEye).norm()
        val spec0 = max(0.0, n.dot(hv))
        // base colour, ambient, diffuse, specular strength, shininess
        val (base, amb, kd, ks, sh) = when (fc.mat) {
            Mat.BODY -> Mat5(paint, 0.36, 0.62, 0.42, 34.0)
            Mat.GLASS -> Mat5(glass, 0.70, 0.22, 0.55, 120.0)
            Mat.TIRE -> Mat5(Color(0xFF171717), 0.65, 0.45, 0.05, 8.0)
            Mat.RIM -> Mat5(Color(0xFFB2B7BD), 0.45, 0.55, 0.55, 22.0)
            Mat.CHROME -> Mat5(Color(0xFFC9CED4), 0.45, 0.40, 0.95, 18.0)
            Mat.HEAD -> Mat5(headOn, 0.85, 0.20, 0.55, 50.0)
            Mat.TAIL -> Mat5(tail, 0.95, 0.10, 0.40, 40.0)
            Mat.LENS -> Mat5(Color(0xFFC6CCD2), 0.70, 0.30, 0.55, 40.0)
            Mat.GRILLE -> Mat5(Color(0xFF1A1B1D), 0.75, 0.35, 0.18, 20.0)
            Mat.TRIM -> Mat5(Color(0xFF26282B), 0.75, 0.35, 0.15, 16.0)
            Mat.PLATE -> Mat5(Color(0xFFEDEDED), 0.55, 0.45, 0.12, 20.0)
            Mat.EUBLUE -> Mat5(Color(0xFF1F4BB5), 0.60, 0.40, 0.10, 20.0)
            Mat.CAVITY -> Mat5(Color(0xFF09090A), 1.0, 0.0, 0.0, 1.0)
        }
        val tinted = if (fc.open > 0.02f && fc.mat != Mat.CAVITY) lerp(base, com.ohm.launcher.ui.theme.Accent.accent, 0.5f * fc.open) else base
        val light = amb + kd * diff + 0.22 * fil
        val spec = if (ks > 0) ks * Math.pow(spec0, sh) else 0.0
        val sky = if (fc.mat == Mat.GLASS || fc.mat == Mat.BODY) 0.05 * max(0.0, n.y) else 0.0
        fun ch(c: Float) = (c * light + spec + sky).toFloat().coerceIn(0f, 1f)
        val col = Color(ch(tinted.red), ch(tinted.green), ch(tinted.blue))
        val path = Path()
        val pts = v.map { scr(it) }
        path.moveTo(pts[0].x, pts[0].y)
        for (k in 1 until pts.size) path.lineTo(pts[k].x, pts[k].y)
        path.close()
        val c = Offset(pts.map { it.x }.average().toFloat(), pts.map { it.y }.average().toFloat())
        // near-axial views: the wheels are hidden by the bumpers, draw them behind the body
        val lay = if (fc.layer == 4 && fc.group > 0 && kotlin.math.abs(sy0) < 0.4) 0 else fc.layer
        buckets[lay.coerceIn(0, 4)].add(Projected(path, ctr.z - fc.bias, col, c, fc.mat, if (lay == 4) fc.group else 0))
    }
    // near wheels are seen through their arch opening only (the wing/bumper hides the rest)
    val clips = archOutlines(bodyType, !lowPower).mapValues { (_, pts) ->
        Path().apply { pts.map { scr(view(it)) }.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }; close() }
    }
    for (layer in 0..4) {
        val l = buckets[layer]
        if (layer != 2) l.sortByDescending { it.depth }
        if (layer == 4 && clips.isNotEmpty()) {
            for ((g, items) in l.groupBy { it.group }) {
                val clip = clips[g]
                if (clip == null) items.forEach { drawPath(it.path, it.color) }
                else clipPath(clip) { items.forEach { drawPath(it.path, it.color); drawPath(it.path, it.color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.1f)) } }
            }
            continue
        }
        for (p in l) {
            drawPath(p.path, p.color)
            // trace the edge in the same colour: hides anti-aliasing seams between neighbouring faces
            if (!lowPower) drawPath(p.path, p.color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.1f))
        }
    }
    // tail-light glow on top (after body)
    if (!lowPower && max(lights, brake) > 0.1f) {
        for (p in buckets[2]) if (p.mat == Mat.TAIL) {
            val rr = (f / dist * 0.4).toFloat()
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFF3020).copy(alpha = 0.45f * max(lights * 0.5f, brake)), Color.Transparent), p.center, rr), rr, p.center)
        }
    }
    if (!lowPower && lights > 0.05f) {
        for (p in buckets[2]) if (p.mat == Mat.HEAD) {
            val rr = (f / dist * 0.55).toFloat()
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFF4D0).copy(alpha = 0.55f * lights), Color.Transparent), p.center, rr), rr, p.center)
        }
    }
}
