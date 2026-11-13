package com.ohm.launcher

import com.ohm.launcher.core.SpeedVolume
import com.ohm.launcher.obd.Elm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeaturesTest {
    @Test fun obdDecoding() {
        assertEquals(1726.0, Elm.rpm(Elm.mode01("41 0C 1A F8\r\r>", "0C")!!), 0.01)
        assertEquals(82.0, Elm.speedKmh(Elm.mode01("410D52", "0D")!!), 0.0)
        assertEquals(90.0, Elm.tempC(Elm.mode01("SEARCHING...\r41 05 82\r", "05")!!), 0.0)
        assertEquals(50.2, Elm.percent(Elm.mode01("412F80", "2F")!!), 0.1)
        assertEquals(14.2, Elm.voltage(Elm.mode01("4142377A", "42")!!), 0.01)
        assertNull(Elm.mode01("NO DATA\r>", "5E"))
        assertNull(Elm.mode01("410D52", "0C"))
    }

    @Test fun supportedPids() {
        // BE 3F A8 13 → 01,03,04,05,06,07,0C,0D,0E,0F,10,11,13,15,1C,1F,20
        val s = Elm.supported("4100BE3FA813", 0x00)
        for (p in listOf("01", "03", "04", "05", "0C", "0D", "10", "11", "13", "15", "1C", "1F", "20")) assertTrue(p, p in s)
        assertTrue("02" !in s && "2F" !in s)
    }

    @Test fun troubleCodes() {
        assertEquals(listOf("P0133", "P0254"), Elm.dtcs("43 01 33 02 54 00 00\r>"))      // older protocols
        assertEquals(listOf("P0420", "C0300"), Elm.dtcs("4302042043 00\r".replace("43 00", "4300")))  // CAN, count byte
        assertEquals(emptyList<String>(), Elm.dtcs("NO DATA"))
    }

    @Test fun fuelFlow() {
        assertEquals(3.2, Elm.fuelFlowLph("diesel", 3.2, 10.0, null)!!, 1e-9)       // PID 5E wins
        assertEquals(10.0 * 3600 / (14.7 * 745), Elm.fuelFlowLph("petrol", null, 10.0, null)!!, 1e-6)
        assertNull(Elm.fuelFlowLph("diesel", null, 10.0, null))                     // diesel needs lambda
        assertEquals(10.0 * 3600 / (14.5 * 1.5 * 832), Elm.fuelFlowLph("diesel", null, 10.0, 1.5)!!, 1e-6)
    }

    @Test fun speedVolumeCurve() {
        assertEquals(0.0, SpeedVolume.boostFor(30.0, 3), 0.0)
        assertEquals(0.0, SpeedVolume.boostFor(120.0, 0), 0.0)
        assertEquals(0.05, SpeedVolume.boostFor(70.0, 1), 1e-9)
        assertEquals(0.10, SpeedVolume.boostFor(200.0, 1), 1e-9)   // capped
        assertEquals(0.20, SpeedVolume.boostFor(130.0, 3), 1e-9)
    }
}

class SpeedLimitTest {
    @Test fun parseMaxspeed() {
        val g = com.ohm.launcher.nav.Geo
        assertEquals(50, g.parseMaxspeed("50"))
        assertEquals(96, g.parseMaxspeed("60 mph"))
        assertEquals(50, g.parseMaxspeed("PT:urban"))
        assertEquals(90, g.parseMaxspeed("PT:rural"))
        assertEquals(120, g.parseMaxspeed("PT:motorway"))
        assertNull(g.parseMaxspeed("none"))
        assertNull(g.parseMaxspeed(""))
    }
}

class VendorMapTest {
    private val json = """{"rules":[
        {"field":"headlights","action":"com.fyt.car.ILL","extra":"state","on":"1"},
        {"field":"reverse","setting":"system/back_car_state","on":"1"},
        {"field":"door_fl","action":"com.fyt.car.DOOR_FL_OPEN","value":"1"},
        {"field":"gear","action":"G","extra":"g","values":{"P":"0","R":"1","N":"2","D":"3"}},
        {"field":"speed","action":"S","extra":"v","scale":0.01},
        {"field":"bogus","action":"X"}]}"""

    @Test fun parsesAndApplies() {
        val r = com.ohm.launcher.car.VendorMap.parse(json)
        assertEquals(5, r.size) // unknown field dropped
        var s = com.ohm.launcher.car.CarState()
        s = com.ohm.launcher.car.VendorMap.apply(r[0], "1", s); assertEquals(true, s.headlights)
        s = com.ohm.launcher.car.VendorMap.apply(r[1], "1", s); assertEquals(com.ohm.launcher.car.Gear.R, s.gear)
        s = com.ohm.launcher.car.VendorMap.apply(r[1], "0", s); assertEquals(com.ohm.launcher.car.Gear.D, s.gear)
        s = com.ohm.launcher.car.VendorMap.apply(r[2], "1", s); assertEquals(true, s.doors.fl)
        s = com.ohm.launcher.car.VendorMap.apply(r[3], "0", s); assertEquals(com.ohm.launcher.car.Gear.P, s.gear)
        s = com.ohm.launcher.car.VendorMap.apply(r[4], "5230", s); assertEquals(52.3f, s.speedKmh!!, 0.001f)
    }
}
