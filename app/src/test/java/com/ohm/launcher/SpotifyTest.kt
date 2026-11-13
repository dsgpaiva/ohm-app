package com.ohm.launcher

import com.ohm.launcher.spotify.Spotify
import com.ohm.launcher.spotify.parseItem
import com.ohm.launcher.spotify.pickImage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyTest {
    @Test fun lrcParsing() {
        val l = Spotify.parseLrc("[00:12.34] Hello\n[01:02.5]World\n[00:05.00][00:30.00] Twice\nno stamp")
        assertEquals(listOf(5000L, 12340L, 30000L, 62500L), l.map { it.timeMs })
        assertEquals("Hello", l[1].text)
        assertEquals("Twice", l[0].text)
    }

    @Test fun trackParsing() {
        val o = JSONObject("""{"type":"track","uri":"spotify:track:1","id":"1","name":"Song","duration_ms":200000,"explicit":true,
            "artists":[{"name":"A","uri":"spotify:artist:a"},{"name":"B","uri":"spotify:artist:b"}],
            "album":{"uri":"spotify:album:x","images":[{"url":"big","width":640},{"url":"mid","width":300},{"url":"small","width":64}]}}""")
        val t = parseItem(o)!!
        assertEquals("A, B", t.subtitle)
        assertEquals("mid", t.image)
        assertEquals("spotify:album:x", t.parentUri)
        assertEquals(listOf("spotify:artist:a", "spotify:artist:b"), t.artistUris)
        assertTrue(t.explicit)
    }

    @Test fun playlistParsingNewAndOldFields() {
        val p = parseItem(JSONObject("""{"type":"playlist","uri":"spotify:playlist:p","id":"p","name":"Mix","owner":{"id":"me","display_name":"Me"},"items":{"total":12},"images":[]}"""))!!
        assertEquals(12, p.count)
        assertEquals("me", p.ownerId)
        val old = parseItem(JSONObject("""{"type":"playlist","uri":"spotify:playlist:q","id":"q","name":"Old","owner":{"id":"x"},"tracks":{"total":3}}"""))!!
        assertEquals(3, old.count)
    }

    @Test fun imagePicking() {
        assertEquals(null, pickImage(JSONArray()))
        assertEquals("only", pickImage(JSONArray("""[{"url":"only","width":null}]""")))
    }
}
