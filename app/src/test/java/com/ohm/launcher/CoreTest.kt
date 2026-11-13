package com.ohm.launcher

import com.ohm.launcher.core.Fmt
import com.ohm.launcher.core.Trip
import com.ohm.launcher.loc.Nmea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CoreTest {
    @Test fun nmeaChecksumAndRmc() {
        // Standard example sentence (checksum 6A)
        val body = Nmea.checked("\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A")
        assertNotNull(body)
        val fix = Nmea.rmc(body!!.split(','), hdop = 1.0f)!!
        assertEquals(48.1173, fix.lat, 1e-4)
        assertEquals(11.5167, fix.lon, 1e-4)
        assertEquals(22.4f * 1.852f, fix.speedKmh!!, 0.01f)
        assertEquals(84.4, fix.bearing!!, 1e-6)
        assertEquals(5f, fix.accuracyM!!, 0.001f)
    }

    @Test fun nmeaRejectsBadChecksumAndVoidFix() {
        assertNull(Nmea.checked("\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*00"))
        assertNull(Nmea.rmc("GPRMC,123519,V,4807.038,N,01131.000,E,0,0,230394,,".split(','), null))
    }

    @Test fun southWestCoordinates() {
        val f = Nmea.rmc("GNRMC,0,A,3842.453,S,00908.190,W,0,0,0,,".split(','), null)!!
        assertEquals(-38.70755, f.lat, 1e-4)
        assertEquals(-9.1365, f.lon, 1e-4)
    }

    @Test fun units() {
        assertEquals("1.5 km", Fmt.distance(1500.0, true))
        assertEquals(62, Fmt.speed(100f, false))
        assertEquals("68°", Fmt.temp(20.0, false))
        assertEquals("39.2 MPG", Fmt.consumption(6.0, "mpgus"))
        assertEquals("16.7 km/L", Fmt.consumption(6.0, "kml"))
        assertEquals("33 psi", Fmt.pressure(2.3, "psi"))
    }

    @Test fun tripRoundTrip() {
        val t = Trip(1000, 61000, 1234.5, 50.0, 88f, "Mégane", 0.08, null)
        val back = Trip.from(t.json())
        assertEquals(t, back)
        assertEquals(1234.5 / 50.0 * 3.6, back.avgKmh, 1e-9)
    }
}
