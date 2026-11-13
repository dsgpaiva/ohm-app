package com.ohm.launcher.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.ohm.launcher.MainActivity
import com.ohm.launcher.Ohm
import com.ohm.launcher.car.Gear
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// ===================================================================== reverse camera

/**
 * Ohm's *own* reaction to reverse gear (SPEC-ideas §1.4): show its camera view or open the chosen
 * camera app, and come back afterwards. It never touches the unit's firmware-level camera
 * switching — turning this off only stops Ohm from reacting.
 */
class ReverseController(private val ctx: Context, private val scope: CoroutineScope) {
    private val _engaged = MutableStateFlow(false)
    /** Reverse is engaged according to the configured trigger. */
    val engaged: StateFlow<Boolean> = _engaged.asStateFlow()
    private val _overlay = MutableStateFlow(false)
    /** Ohm shows its built-in reverse view (demo, or no camera app chosen). */
    val overlay: StateFlow<Boolean> = _overlay.asStateFlow()
    private var launchedApp = false
    private var registered: BroadcastReceiver? = null

    fun init() {
        scope.launch {
            combine(Ohm.prefs.cameraTrigger.state, Ohm.prefs.cameraAction.state, Ohm.prefs.cameraExtra.state) { t, a, e -> Triple(t, a, e) }
                .collect { (t, a, e) -> configure(t, a, e) }
        }
        scope.launch {
            Ohm.car.state.collect { s ->
                val trig = Ohm.prefs.cameraTrigger.value
                // Demo always uses the gear selector; real units use CAN gear only if chosen.
                if (Ohm.car.sourceId.value == "demo" || trig == "can") set(s.gear == Gear.R)
            }
        }
    }

    /** The driver can dismiss Ohm's own reverse view; the car's camera is unaffected. */
    fun hideOverlay() { _overlay.value = false }

    private fun configure(trigger: String, action: String, extra: String) {
        registered?.let { runCatching { ctx.unregisterReceiver(it) } }
        registered = null
        if (trigger == "can" || action.isBlank()) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                i ?: return
                val on = if (extra.isBlank()) true else {
                    val (k, v) = extra.split('=', limit = 2).let { it[0].trim() to it.getOrNull(1)?.trim() }
                    val got = i.extras?.get(k)
                    if (v != null) got?.toString() == v else when (got) { is Boolean -> got; is Number -> got.toInt() != 0; is String -> got == "1" || got.equals("true", true); else -> false }
                }
                Ohm.car.raw("reverse.$trigger", "${i.action} → $on")
                set(on)
            }
        }
        ContextCompat.registerReceiver(ctx, r, IntentFilter(action), ContextCompat.RECEIVER_EXPORTED)
        registered = r
        OhmLog.i("Reverse", "Listening for $trigger broadcast $action")
    }

    private fun set(on: Boolean) {
        if (on == _engaged.value) return
        _engaged.value = on
        if (!Ohm.prefs.cameraAuto.value) { _overlay.value = false; return }
        if (on) {
            val app = Ohm.prefs.cameraApp.value
            if (Ohm.car.sourceId.value != "demo" && app.isNotEmpty() && Ohm.apps.launchPkg(app, record = false)) {
                launchedApp = true; _overlay.value = false
            } else _overlay.value = true
        } else {
            _overlay.value = false
            if (launchedApp && Ohm.prefs.cameraReturn.value) {
                runCatching {
                    ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                }
            }
            launchedApp = false
        }
    }
}

// ===================================================================== trips

data class Trip(
    val start: Long,
    val end: Long,
    val distanceM: Double,
    val movingS: Double,
    val maxKmh: Float,
    val vehicle: String,
    /** Estimated from the vehicle's typical consumption (no fuel data on the CAN yet). */
    val fuelL: Double?,
    val cost: Double?,
    /** True when fuel was measured by OBD-II rather than estimated from typical consumption. */
    val fuelMeasured: Boolean = false,
) {
    val durationS get() = (end - start) / 1000.0
    val avgKmh get() = if (movingS > 0) distanceM / movingS * 3.6 else 0.0

    fun json() = JSONObject().put("s", start).put("e", end).put("d", distanceM).put("m", movingS).put("x", maxKmh.toDouble())
        .put("v", vehicle).put("f", fuelL ?: -1.0).put("c", cost ?: -1.0).put("fm", fuelMeasured)

    companion object {
        fun from(o: JSONObject) = Trip(o.getLong("s"), o.getLong("e"), o.getDouble("d"), o.getDouble("m"), o.getDouble("x").toFloat(),
            o.optString("v"), o.optDouble("f", -1.0).takeIf { it >= 0 }, o.optDouble("c", -1.0).takeIf { it >= 0 }, o.optBoolean("fm"))
    }
}

/** Records trips automatically from speed + position (SPEC-ideas §2.3). */
class Trips(private val scope: CoroutineScope) {
    private val _current = MutableStateFlow<Trip?>(null)
    val current: StateFlow<Trip?> = _current.asStateFlow()
    private val _history = MutableStateFlow<List<Trip>>(emptyList())
    val history: StateFlow<List<Trip>> = _history.asStateFlow()

    fun init() {
        _history.value = runCatching { JSONArray(Ohm.prefs.trips.value).let { a -> (0 until a.length()).map { Trip.from(a.getJSONObject(it)) } } }.getOrDefault(emptyList())
        scope.launch {
            var lastPos: LatLon? = null
            var stillSince = 0L
            while (isActive) {
                delay(1000)
                val nav = Ohm.nav.state.value
                val car = Ohm.car.state.value
                val kmh = car.speedKmh ?: nav.speedKmh
                val now = System.currentTimeMillis()
                var t = _current.value
                if (t == null) {
                    if (kmh > 5) { t = Trip(now, now, 0.0, 0.0, kmh, Ohm.prefs.vehicleName.value, null, null); lastPos = nav.pos; stillSince = 0 }
                    else continue
                }
                val step = lastPos?.let { GeoMath.dist(it, nav.pos) } ?: 0.0
                lastPos = nav.pos
                val plausible = step < 75 // ignore GPS jumps (> 270 km/h)
                t = t.copy(end = now, distanceM = t.distanceM + if (plausible) step else 0.0,
                    movingS = t.movingS + if (kmh > 2) 1 else 0, maxKmh = maxOf(t.maxKmh, kmh))
                // Fuel: measured by OBD-II when available (integrated each second), else estimated.
                val obdRate = Ohm.obd.data.value.takeIf { it.connected }?.fuelLph
                val fuel: Double?
                if (obdRate != null) {
                    fuel = (if (t.fuelMeasured) t.fuelL ?: 0.0 else 0.0) + obdRate / 3600.0
                    t = t.copy(fuelMeasured = true)
                } else if (t.fuelMeasured) fuel = t.fuelL
                else {
                    val cons = Ohm.prefs.consumption.value
                    fuel = if (cons > 0) t.distanceM / 1000 * cons / 100 else null
                }
                val price = Ohm.prefs.fuelPrice.value
                t = t.copy(fuelL = fuel, cost = if (fuel != null && price > 0) fuel * price else null)
                _current.value = t
                if (kmh < 1) { if (stillSince == 0L) stillSince = now } else stillSince = 0
                val parked = car.gear == Gear.P && stillSince > 0 && now - stillSince > 30_000
                if (parked || (stillSince > 0 && now - stillSince > 180_000)) finish()
            }
        }
    }

    fun finish() {
        val t = _current.value ?: return
        _current.value = null
        if (t.distanceM < 200) return
        _history.value = (listOf(t) + _history.value).take(50)
        Ohm.prefs.trips.set(JSONArray(_history.value.map { it.json() }).toString())
        OhmLog.i("Trips", "Trip saved: ${"%.1f".format(t.distanceM / 1000)} km")
    }

    fun clear() { _history.value = emptyList(); Ohm.prefs.trips.set("[]") }
}
