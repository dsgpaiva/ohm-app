package com.ohm.launcher.loc

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.ohm.launcher.Ohm
import com.ohm.launcher.core.OhmLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * One position report from one provider. [receivedAt] is our own monotonic clock
 * (elapsedRealtime) so freshness never depends on another device's wall clock.
 */
data class Fix(
    val provider: String,
    val lat: Double,
    val lon: Double,
    val speedKmh: Float?,
    val bearing: Double?,
    val accuracyM: Float?,
    /** Time the source says the fix was taken (epoch ms), informative only. */
    val fixTime: Long,
    val receivedAt: Long = SystemClock.elapsedRealtime(),
) {
    fun ageMs(now: Long = SystemClock.elapsedRealtime()) = now - receivedAt
    val quality: Quality get() {
        val age = ageMs(); val acc = accuracyM ?: 50f
        return when {
            age > STALE_MS -> Quality.STALE
            age <= 3000 && acc <= 30f -> Quality.GOOD
            acc <= 100f -> Quality.FAIR
            else -> Quality.POOR
        }
    }

    companion object { const val STALE_MS = 6000L }
}

enum class Quality { GOOD, FAIR, POOR, STALE }

data class CompanionStatus(val device: String, val battery: Int?, val network: String?, val lastSeen: Long, val address: String)

data class LocationStatus(
    val chosen: Fix? = null,
    /** True when the user's preferred provider isn't fresh and another one is used. */
    val fallback: Boolean = false,
    val satsUsed: Int = 0,
    val satsVisible: Int = 0,
    val rejectedPackets: Int = 0,
)

val ProviderNames = listOf("unit", "companion", "external")

/**
 * Merges the head-unit GPS, the Ohm Companion phone app and an external NMEA receiver.
 * Selection: the preferred provider if its fix is fresh and accurate enough, otherwise the
 * best fresh one (GOOD before FAIR before POOR; ties → unit, companion, external).
 */
class LocationHub(private val ctx: Context, private val scope: CoroutineScope) {
    private val _fixes = MutableStateFlow<Map<String, Fix>>(emptyMap())
    val fixes: StateFlow<Map<String, Fix>> = _fixes.asStateFlow()
    private val _status = MutableStateFlow(LocationStatus())
    val status: StateFlow<LocationStatus> = _status.asStateFlow()
    private val _companion = MutableStateFlow<CompanionStatus?>(null)
    val companion: StateFlow<CompanionStatus?> = _companion.asStateFlow()

    /** Emits every chosen fix (Nav subscribes). */
    var onFix: ((Fix) -> Unit)? = null
    /** Called once when every provider has gone stale (no fresh fix for > 6 s). */
    var onStale: (() -> Unit)? = null

    private val lm = ctx.getSystemService(LocationManager::class.java)
    private var lastUnitGps = 0L
    private var udpJob: Job? = null
    private var nmeaJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun lastFix(p: String) = _fixes.value[p]

    fun init() {
        startUnitGps()
        startCompanionReceiver()
        scope.launch { Ohm.prefs.externalGpsPort.state.collect { startNmeaReceiver(it) } }
        // re-evaluate once a second so stale providers are dropped even without new packets
        scope.launch { while (isActive) { delay(1000); choose(null) } }
    }

    // ------------------------------------------------------------------ head-unit GPS

    private val unitListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onUnit(location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    }

    private val gnssCb = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            _status.update { it.copy(satsUsed = used, satsVisible = status.satelliteCount) }
        }
    }

    private fun hasPermission() =
        ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun startUnitGps() {
        if (!hasPermission() || lm == null) return
        runCatching { lm.removeUpdates(unitListener) }
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (runCatching { lm.isProviderEnabled(p) }.getOrDefault(false)) {
                runCatching { lm.requestLocationUpdates(p, if (p == LocationManager.GPS_PROVIDER) 500L else 2000L, 0f, unitListener, Looper.getMainLooper()) }
                    .onFailure { OhmLog.w("GPS", "Can't start $p", it) }
            }
        }
        runCatching { lm.registerGnssStatusCallback(gnssCb, Handler(Looper.getMainLooper())) }
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { Ohm.nav.seedPosition(it.latitude, it.longitude) }
        OhmLog.i("GPS", "Head-unit location started")
    }

    private fun onUnit(loc: Location) {
        // Prefer real GPS; network fixes only when GPS has been silent for 10 s.
        if (loc.provider == LocationManager.NETWORK_PROVIDER && SystemClock.elapsedRealtime() - lastUnitGps < 10_000) return
        if (loc.provider == LocationManager.GPS_PROVIDER) lastUnitGps = SystemClock.elapsedRealtime()
        put(Fix("unit", loc.latitude, loc.longitude,
            if (loc.hasSpeed()) loc.speed * 3.6f else null,
            if (loc.hasBearing()) loc.bearing.toDouble() else null,
            if (loc.hasAccuracy()) loc.accuracy else null, loc.time))
    }

    // ------------------------------------------------------------------ Ohm Companion (UDP)

    private fun startCompanionReceiver() {
        runCatching {
            val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
            multicastLock = wm.createMulticastLock("ohm-companion").apply { setReferenceCounted(false); acquire() }
        }
        udpJob?.cancel()
        udpJob = scope.launch {
            withContext(Dispatchers.IO) {
                val sock = runCatching { DatagramSocket(null).apply { reuseAddress = true; broadcast = true; soTimeout = 1000; bind(InetSocketAddress(COMPANION_PORT)) } }
                    .onFailure { OhmLog.w("Companion", "Can't listen on UDP $COMPANION_PORT", it) }.getOrNull() ?: return@withContext
                OhmLog.i("Companion", "Listening on UDP $COMPANION_PORT")
                val buf = ByteArray(4096)
                while (isActive) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try { sock.receive(pkt) } catch (_: SocketTimeoutException) { continue } catch (e: Exception) { OhmLog.w("Companion", "receive", e); delay(1000); continue }
                    val text = String(pkt.data, 0, pkt.length)
                    val o = runCatching { JSONObject(text) }.getOrNull() ?: continue
                    if (o.optInt("ohm") != 1) continue
                    if (o.optString("code") != Ohm.prefs.companionCode.value) {
                        _status.update { it.copy(rejectedPackets = it.rejectedPackets + 1) }
                        continue
                    }
                    val from = pkt.address.hostAddress ?: "?"
                    withContext(Dispatchers.Main) { onCompanion(o, from) }
                    // acknowledge so the phone can show "connected"
                    runCatching {
                        val ack = """{"ohm":1,"type":"ack","name":"Ohm","vehicle":${JSONObject.quote(Ohm.prefs.vehicleName.value)}}""".toByteArray()
                        sock.send(DatagramPacket(ack, ack.size, pkt.socketAddress))
                    }
                }
                sock.close()
            }
        }
    }

    private fun onCompanion(o: JSONObject, from: String) {
        _companion.value = CompanionStatus(o.optString("dev", "Phone"), o.optInt("bat", -1).takeIf { it >= 0 }, o.optString("net").ifEmpty { null }, SystemClock.elapsedRealtime(), from)
        when (o.optString("type")) {
            "loc" -> put(Fix("companion", o.getDouble("lat"), o.getDouble("lon"),
                if (o.has("spd")) (o.getDouble("spd") * 3.6).toFloat() else null,
                if (o.has("brg")) o.getDouble("brg") else null,
                if (o.has("acc")) o.getDouble("acc").toFloat() else null,
                o.optLong("time", System.currentTimeMillis())))
            "notif" -> Ohm.banners.phoneNotification(o.optString("app"), o.optString("title"), o.optString("text"))
            "dest" -> {
                // The phone sends to every network it's on, so the same message can arrive twice.
                val id = o.optString("id")
                if (id.isNotEmpty() && id == lastDestId) return
                lastDestId = id
                Ohm.nav.destinationFromPhone(
                    name = o.optString("name"),
                    lat = if (o.has("lat")) o.getDouble("lat") else null,
                    lon = if (o.has("lon")) o.getDouble("lon") else null,
                    query = o.optString("query").ifEmpty { null },
                )
            }
        }
    }

    private var lastDestId: String? = null

    // ------------------------------------------------------------------ external GPS (NMEA over UDP)

    private fun startNmeaReceiver(port: Int) {
        nmeaJob?.cancel()
        nmeaJob = scope.launch {
            withContext(Dispatchers.IO) {
                val sock = runCatching { DatagramSocket(null).apply { reuseAddress = true; soTimeout = 1000; bind(InetSocketAddress(port)) } }
                    .onFailure { OhmLog.w("NMEA", "Can't listen on UDP $port", it) }.getOrNull() ?: return@withContext
                OhmLog.i("NMEA", "Listening for NMEA on UDP $port")
                val buf = ByteArray(2048)
                var hdop: Float? = null
                while (isActive) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try { sock.receive(pkt) } catch (_: SocketTimeoutException) { continue } catch (e: Exception) { delay(1000); continue }
                    for (line in String(pkt.data, 0, pkt.length).lines()) {
                        val s = Nmea.checked(line.trim()) ?: continue
                        val f = s.split(',')
                        when {
                            f[0].endsWith("GGA") -> hdop = f.getOrNull(8)?.toFloatOrNull()
                            f[0].endsWith("RMC") -> Nmea.rmc(f, hdop)?.let { fix -> withContext(Dispatchers.Main) { put(fix) } }
                        }
                    }
                }
                sock.close()
            }
        }
    }

    // ------------------------------------------------------------------ selection

    private fun put(f: Fix) {
        _fixes.update { it + (f.provider to f) }
        choose(f)
    }

    private fun choose(newFix: Fix?) {
        val pref = Ohm.prefs.locationProvider.value
        val fresh = _fixes.value.values.filter { it.quality != Quality.STALE }
        val preferred = if (pref != "auto") fresh.firstOrNull { it.provider == pref && it.quality <= Quality.FAIR } else null
        val best = preferred ?: fresh.sortedWith(compareBy<Fix>({ it.quality.ordinal }, { ProviderNames.indexOf(it.provider) })).firstOrNull()
        val prev = _status.value.chosen
        _status.update { it.copy(chosen = best, fallback = pref != "auto" && best != null && best.provider != pref) }
        if (best == null && prev != null) onStale?.invoke()
        if (best != null && prev?.provider != best.provider) OhmLog.i("Location", "Using ${best.provider} (${best.quality})")
        if (best != null && newFix != null && best === newFix) onFix?.invoke(best)
    }

    fun release() {
        runCatching { lm?.removeUpdates(unitListener) }
        runCatching { lm?.unregisterGnssStatusCallback(gnssCb) }
        udpJob?.cancel(); nmeaJob?.cancel()
        runCatching { multicastLock?.release() }
    }

    companion object {
        const val COMPANION_PORT = 47017
        @Suppress("unused") private val sdk = Build.VERSION.SDK_INT
    }
}

/** Minimal NMEA 0183 parsing ($--RMC position/speed/course, $--GGA HDOP). */
object Nmea {
    fun checked(line: String): String? {
        if (!line.startsWith("$")) return null
        val star = line.indexOf('*')
        if (star < 0) return line.substring(1)
        val body = line.substring(1, star)
        val sum = line.substring(star + 1).take(2).toIntOrNull(16) ?: return null
        var x = 0
        for (ch in body) x = x xor ch.code
        return if (x == sum) body else null
    }

    private fun coord(v: String, hemi: String, degDigits: Int): Double? {
        if (v.length < degDigits + 2) return null
        val deg = v.substring(0, degDigits).toDoubleOrNull() ?: return null
        val min = v.substring(degDigits).toDoubleOrNull() ?: return null
        val d = deg + min / 60
        return if (hemi == "S" || hemi == "W") -d else d
    }

    fun rmc(f: List<String>, hdop: Float?): Fix? {
        if (f.getOrNull(2) != "A") return null
        val lat = coord(f.getOrNull(3) ?: return null, f.getOrNull(4) ?: "N", 2) ?: return null
        val lon = coord(f.getOrNull(5) ?: return null, f.getOrNull(6) ?: "E", 3) ?: return null
        val kn = f.getOrNull(7)?.toFloatOrNull()
        val course = f.getOrNull(8)?.toDoubleOrNull()
        return Fix("external", lat, lon, kn?.let { it * 1.852f }, course, hdop?.let { it * 5f }, System.currentTimeMillis())
    }
}
