package com.ohm.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaceParserTest {
    @Test fun googleMapsFullUrl() {
        val c = PlaceParser.coords("https://www.google.com/maps/place/Torre+de+Bel%C3%A9m/@38.6916,-9.2160,17z/data=!3m1!4b1!4m6!3m5!1s0x0:0x0!8m2!3d38.6915837!4d-9.2159767")!!
        assertEquals(38.6915837, c.first, 1e-7)   // the !3d!4d pin wins over the map centre
        assertEquals(-9.2159767, c.second, 1e-7)
    }

    @Test fun whatsappAndPlainText() {
        assertEquals(38.7075 to -9.1365, PlaceParser.coords("https://maps.google.com/?q=38.7075,-9.1365"))
        assertEquals(41.1496 to -8.6109, PlaceParser.coords("Meet me here: 41.1496, -8.6109"))
    }

    @Test fun noCoordinates() {
        assertNull(PlaceParser.coords("Pastéis de Belém, R. de Belém 84"))
        assertNull(PlaceParser.coords("version 1.2, build 3.4"))
    }

    @Test fun sharedTextWithCoords() {
        val p = PlaceParser.fromText("Torre de Belém\nhttps://maps.google.com/?q=38.6916,-9.2160")
        assertEquals("Torre de Belém", p.name)
        assertEquals(38.6916, p.lat!!, 1e-9)
    }
}
