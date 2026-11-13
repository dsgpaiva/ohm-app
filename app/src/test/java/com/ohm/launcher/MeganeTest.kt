package com.ohm.launcher

import com.ohm.launcher.car.CarPose
import com.ohm.launcher.car.buildCar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeganeTest {
    @Test fun dimensionsAndCost() {
        for (body in listOf("estate", "hatch", "coupe")) {
            val t0 = System.nanoTime()
            val f = buildCar(body, CarPose(), true)
            val first = (System.nanoTime() - t0) / 1e6
            val t1 = System.nanoTime(); repeat(20) { buildCar(body, CarPose(doorFL = 0.5f, trunk = 0.5f), true) }
            val per = (System.nanoTime() - t1) / 1e6 / 20
            val pts = f.flatMap { it.pts }
            val len = pts.maxOf { it.z } - pts.minOf { it.z }
            val wid = pts.filter { it.y in 0.3..0.9 }.let { p -> p.maxOf { it.x } - p.minOf { it.x } }
            val hi = pts.maxOf { it.y }
            println("$body: ${f.size} faces, length %.2f, body width %.2f, height %.2f, first build %.0f ms, per frame %.2f ms".format(len, wid, hi, first, per))
            assertTrue(f.size > 900)
            assertTrue(f.all { it.pts.size >= 3 })
        }
        val e = buildCar("estate", CarPose(), true).flatMap { it.pts }
        assertEquals(4.56, e.maxOf { it.z } - e.minOf { it.z }, 0.03)       // Grandtour length
        assertEquals(1.51, e.maxOf { it.y }, 0.04)                          // height incl. roof rails
    }
}
