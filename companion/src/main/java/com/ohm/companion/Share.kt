package com.ohm.companion

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.UUID

/** What a shared link/text turned into. */
data class SharedPlace(val name: String, val lat: Double?, val lon: Double?, val query: String?)

/**
 * Turns shared text or links into a destination:
 *  - geo:38.7,-9.1?q=38.7,-9.1(Name) / geo:0,0?q=Some+address
 *  - Google Maps links (incl. maps.app.goo.gl short links, resolved by following redirects)
 *  - WhatsApp locations (maps.google.com/?q=lat,lng), plain "lat, lng" text
 *  - anything else → sent as a search text, which Ohm geocodes.
 */
object PlaceParser {
    private val coordPatterns = listOf(
        Regex("""!3d(-?\d{1,2}\.\d+)!4d(-?\d{1,3}\.\d+)"""),           // Google Maps place data (most precise)
        Regex("""[?&](?:q|query|ll|destination|daddr)=(-?\d{1,2}\.\d+)(?:,|%2C)\s*(-?\d{1,3}\.\d+)"""),
        Regex("""@(-?\d{1,2}\.\d+),(-?\d{1,3}\.\d+)"""),                // map centre in /place/ URLs
        Regex("""(?<![\d.])(-?\d{1,2}\.\d{3,}),\s*(-?\d{1,3}\.\d{3,})"""), // bare "lat, lng"
    )

    fun coords(s: String): Pair<Double, Double>? {
        for (re in coordPatterns) {
            val m = re.find(s) ?: continue
            val lat = m.groupValues[1].toDoubleOrNull() ?: continue
            val lon = m.groupValues[2].toDoubleOrNull() ?: continue
            if (lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)) return lat to lon
        }
        return null
    }

    /** From a geo: URI. */
    fun fromGeo(uri: Uri): SharedPlace {
        val ssp = uri.schemeSpecificPart ?: ""
        val q = uri.getQueryParameter("q") ?: ssp.substringAfter("q=", "").ifEmpty { null }
        val label = q?.let { Regex("""\(([^)]*)\)""").find(it)?.groupValues?.get(1) }.orEmpty()
        val c = q?.let { coords(it) } ?: coords(ssp.substringBefore('?'))
        return if (c != null) SharedPlace(label, c.first, c.second, null)
        else SharedPlace(label, null, null, q?.replace(Regex("""\([^)]*\)"""), "")?.trim())
    }

    /** From shared text (usually "Name\nAddress\nhttps://maps.app.goo.gl/…"). Network access for short links. */
    fun fromText(text: String): SharedPlace {
        val url = Regex("""https?://\S+""").find(text)?.value
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("http") }
        val name = lines.firstOrNull().orEmpty()
        coords(text)?.let { return SharedPlace(name, it.first, it.second, null) }
        if (url != null) {
            val final = resolve(url)
            coords(final)?.let { return SharedPlace(name, it.first, it.second, null) }
            // /maps/place/Some+Place/… without coordinates → search by the place name.
            Regex("""/place/([^/@?]+)""").find(final)?.let { return SharedPlace(name, null, null, Uri.decode(it.groupValues[1]).replace('+', ' ')) }
        }
        val query = lines.joinToString(", ").ifEmpty { text.trim() }
        return SharedPlace(name, null, null, query)
    }

    /** Follows redirects by hand (short links) and returns the final URL. */
    private fun resolve(start: String): String {
        var url = start
        repeat(6) {
            val c = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull() ?: return url
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 5000; c.readTimeout = 5000
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) OhmCompanion")
                val code = c.responseCode
                val loc = c.getHeaderField("Location")
                if (code in 300..399 && loc != null) url = URL(URL(url), loc).toString() else return url
            } catch (e: Exception) { return url } finally { c.disconnect() }
        }
        return url
    }
}

/**
 * Invisible activity behind "Share › Ohm Companion" and geo: links: works out the place,
 * sends it to Ohm and waits briefly for the head unit's acknowledgement.
 */
class ShareActivity : Activity() {
    private val scope: CoroutineScope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = intent
        scope.launch {
            val place = withContext(Dispatchers.IO) {
                runCatching {
                    when {
                        i.action == Intent.ACTION_VIEW && i.data?.scheme == "geo" -> PlaceParser.fromGeo(i.data!!)
                        i.action == Intent.ACTION_VIEW && i.data != null -> PlaceParser.fromText(i.data.toString())
                        else -> PlaceParser.fromText(listOfNotNull(i.getStringExtra(Intent.EXTRA_SUBJECT), i.getStringExtra(Intent.EXTRA_TEXT)).joinToString("\n"))
                    }
                }.getOrNull()
            }
            if (place == null || (place.lat == null && place.query.isNullOrBlank())) {
                toast("Couldn't find a place in what was shared"); finish(); return@launch
            }
            if (Link.code(this@ShareActivity).length != 4) { toast("Set the pairing code in Ohm Companion first"); finish(); return@launch }
            val ok = withContext(Dispatchers.IO) { sendDestination(place) }
            toast(if (ok) "Sent to Ohm: ${place.name.ifBlank { place.query ?: "location" }}" else "Ohm didn't answer — is the head unit on the same Wi-Fi/hotspot?")
            finish()
        }
    }

    private fun sendDestination(p: SharedPlace): Boolean {
        val o = Link.base(this, "dest").put("id", UUID.randomUUID().toString()).put("name", p.name)
        if (p.lat != null && p.lon != null) o.put("lat", p.lat).put("lon", p.lon)
        p.query?.let { o.put("query", it) }
        val bytes = o.toString().toByteArray()
        DatagramSocket().use { s ->
            s.broadcast = true; s.soTimeout = 700
            val buf = ByteArray(512)
            repeat(4) {
                for (t in Link.targets(this)) runCatching { s.send(DatagramPacket(bytes, bytes.size, t, Link.PORT)) }
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    s.receive(pkt)
                    if (JSONObject(String(pkt.data, 0, pkt.length)).optString("type") == "ack") return true
                } catch (_: SocketTimeoutException) {} catch (_: Exception) {}
            }
        }
        return false
    }

    private fun toast(s: String) = Toast.makeText(applicationContext, s, Toast.LENGTH_LONG).show()

    override fun onDestroy() { super.onDestroy(); scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
}
