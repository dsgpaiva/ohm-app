package com.ohm.launcher

import com.ohm.launcher.car.CarPose
import com.ohm.launcher.car.Mat
import com.ohm.launcher.car.V3
import com.ohm.launcher.car.buildCar
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/** Renders the car to PNGs (build/car-preview/) with the same projection & lighting as the app. */
class CarPreview {
    private fun render(name: String, body: String, yawDeg: Double, pitchDeg: Double, pose: CarPose = CarPose(), w: Int = 1600, h: Int = 1000, dist: Double = 8.2) {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(0x17, 0x17, 0x17); g.fillRect(0, 0, w, h)
        val yaw = Math.toRadians(yawDeg); val pitch = Math.toRadians(pitchDeg)
        val f = w * 1.3; val cx = w / 2.0; val cy = h * 0.55
        val cy0 = cos(yaw); val sy0 = sin(yaw); val cp = cos(pitch); val sp = sin(pitch)
        fun view(p: V3): V3 {
            val x1 = p.x * cy0 - p.z * sy0; val z1 = p.x * sy0 + p.z * cy0; val y = p.y - 0.55
            return V3(x1, y * cp + z1 * sp, z1 * cp - y * sp + dist)
        }
        val nearRight = -sin(yaw) > 0; val camBehind = -cos(yaw) < 0
        val faces = buildCar(body, pose, nearRight, camBehind)
        val key = V3(-0.45, 0.75, -0.48).norm(); val fill = V3(0.65, 0.15, -0.45).norm()
        val paint = floatArrayOf(0.913f, 0.917f, 0.925f)
        data class P(val path: Path2D, val depth: Double, val color: Color, val layer: Int, val group: Int)
        val list = ArrayList<P>()
        for (fc in faces) {
            val v = fc.pts.map { view(it) }
            var nx = 0.0; var ny = 0.0; var nz = 0.0
            for (k in v.indices) { val a = v[k]; val b = v[(k + 1) % v.size]; nx += (a.y - b.y) * (a.z + b.z); ny += (a.z - b.z) * (a.x + b.x); nz += (a.x - b.x) * (a.y + b.y) }
            var n = V3(nx, ny, nz).norm(); if (n.dot(n) < 0.5) continue
            val ctr = V3(v.sumOf { it.x } / v.size, v.sumOf { it.y } / v.size, v.sumOf { it.z } / v.size)
            val facing = n.dot(ctr) < 0
            if (!facing && !fc.doubleSided) continue
            if (!facing) n = n * -1.0
            val toEye = (ctr * -1.0).norm(); val hv = (key + toEye).norm()
            if (fc.layer == 2 && facing && n.dot(toEye) < 0.12) continue
            val diff = max(0.0, n.dot(key)); val fil = max(0.0, n.dot(fill)); val s0 = max(0.0, n.dot(hv))
            val (base, amb, kd, ks, sh) = when (fc.mat) {
                Mat.BODY -> listOf(paint, 0.36, 0.62, 0.42, 34.0)
                Mat.GLASS -> listOf(floatArrayOf(0.055f, 0.07f, 0.085f), 0.70, 0.22, 0.55, 120.0)
                Mat.TIRE -> listOf(floatArrayOf(0.09f, 0.09f, 0.09f), 0.65, 0.45, 0.05, 8.0)
                Mat.RIM -> listOf(floatArrayOf(0.70f, 0.72f, 0.74f), 0.45, 0.55, 0.55, 22.0)
                Mat.CHROME -> listOf(floatArrayOf(0.79f, 0.81f, 0.83f), 0.45, 0.40, 0.95, 18.0)
                Mat.HEAD -> listOf(floatArrayOf(0.42f, 0.45f, 0.49f), 0.85, 0.2, 0.55, 50.0)
                Mat.TAIL -> listOf(floatArrayOf(0.48f, 0.09f, 0.07f), 0.95, 0.1, 0.4, 40.0)
                Mat.LENS -> listOf(floatArrayOf(0.78f, 0.80f, 0.82f), 0.7, 0.3, 0.55, 40.0)
                Mat.GRILLE -> listOf(floatArrayOf(0.10f, 0.105f, 0.11f), 0.75, 0.35, 0.18, 20.0)
                Mat.TRIM -> listOf(floatArrayOf(0.15f, 0.155f, 0.17f), 0.75, 0.35, 0.15, 16.0)
                Mat.PLATE -> listOf(floatArrayOf(0.93f, 0.93f, 0.93f), 0.55, 0.45, 0.12, 20.0)
                Mat.EUBLUE -> listOf(floatArrayOf(0.12f, 0.29f, 0.71f), 0.6, 0.4, 0.1, 20.0)
                Mat.CAVITY -> listOf(floatArrayOf(0.035f, 0.035f, 0.04f), 1.0, 0.0, 0.0, 1.0)
            }
            base as FloatArray; amb as Double; kd as Double; ks as Double; sh as Double
            val lit = amb + kd * diff + 0.22 * fil; val spec = if (ks > 0) ks * s0.pow(sh) else 0.0
            val sky = if (fc.mat == Mat.GLASS || fc.mat == Mat.BODY) 0.05 * max(0.0, n.y) else 0.0
            fun ch(c: Float) = (c * lit + spec + sky).toFloat().coerceIn(0f, 1f)
            val path = Path2D.Double()
            v.forEachIndexed { i, p -> val sx = cx + p.x * f / p.z; val sy = cy - p.y * f / p.z; if (i == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy) }
            path.closePath()
            val lay = if (fc.layer == 4 && fc.group > 0 && kotlin.math.abs(sy0) < 0.4) 0 else fc.layer
            list += P(path, ctr.z - fc.bias, Color(ch(base[0]), ch(base[1]), ch(base[2])), lay, if (lay == 4) fc.group else 0)
        }
        g.stroke = BasicStroke(1.2f)
        for (layer in 0..4) {
            val l = list.filter { it.layer == layer }.let { if (layer != 2) it.sortedByDescending { p -> p.depth } else it }
            for (p in l) {
                val clip = if (p.group > 0) com.ohm.launcher.car.archOutlines(body, true)[p.group] else null
                if (clip != null) {
                    val cp = Path2D.Double(); clip.map { view(it) }.forEachIndexed { i, q -> val sx = cx + q.x * f / q.z; val sy = cy - q.y * f / q.z; if (i == 0) cp.moveTo(sx, sy) else cp.lineTo(sx, sy) }; cp.closePath()
                    g.clip = cp
                }
                g.color = p.color; g.fill(p.path); g.draw(p.path)
                g.clip = null
            }
        }
        val dir = File("build/car-preview"); dir.mkdirs()
        ImageIO.write(img, "png", File(dir, "$name.png"))
    }

    @Test fun previews() {
        render("estate_rear_left", "estate", -38.0, 20.0)
        render("estate_front_left", "estate", 145.0, 16.0)
        render("estate_side_right", "estate", -90.0, 6.0, dist = 9.5)
        render("estate_front", "estate", 180.0, 8.0)
        render("estate_rear", "estate", 0.0, 10.0)
        render("estate_open", "estate", 32.0, 22.0, CarPose(doorFL = 1f, trunk = 1f, hood = 1f))
        render("hatch_rear_left", "hatch", -38.0, 20.0)
    }
}
