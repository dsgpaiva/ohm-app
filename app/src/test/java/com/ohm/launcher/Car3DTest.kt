package com.ohm.launcher

import com.ohm.launcher.car.CarPose
import com.ohm.launcher.car.buildCar
import org.junit.Assert.assertTrue
import org.junit.Test

class Car3DTest {
    private fun maxY(p: CarPose) = buildCar("estate", p, true).flatMap { it.pts }.maxOf { it.y }
    private fun minX(p: CarPose) = buildCar("estate", p, true).flatMap { it.pts }.minOf { it.x }

    @Test fun trunkOpensUpwards() {
        val closed = maxY(CarPose()); val open = maxY(CarPose(trunk = 1f))
        println("max y closed=$closed open=$open")
        assertTrue("open tailgate should rise above the roof", open > closed + 0.04)
    }

    @Test fun driverDoorSwingsOut() {
        val closed = minX(CarPose()); val open = minX(CarPose(doorFL = 1f))
        println("min x closed=$closed open=$open")
        assertTrue("open driver door should stick out to the left", open < closed - 0.3)
    }
}
