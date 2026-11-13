package com.ohm.launcher.car

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Gear { P, R, N, D }

data class Doors(
    val fl: Boolean = false, val fr: Boolean = false,
    val rl: Boolean = false, val rr: Boolean = false,
    val trunk: Boolean = false, val hood: Boolean = false,
) {
    val anyOpen get() = fl || fr || rl || rr || trunk || hood
}

/** Everything the UI knows about the car. Null = not available from the current source. */
data class CarState(
    val gear: Gear? = null,
    val speedKmh: Float? = null,
    val headlights: Boolean? = null,
    val braking: Boolean = false,
    val doors: Doors = Doors(),
    val locked: Boolean? = null,
    val outsideTempC: Float? = null,
    val passengerAirbagOff: Boolean? = null,
    // From an OBD-II adapter, when connected
    val rpm: Double? = null,
    val coolantC: Double? = null,
    val fuelPct: Double? = null,
    val batteryV: Double? = null,
    /** Fuel flow, L/h. */
    val fuelLph: Double? = null,
)

/** A provider of car data (SPEC.md §8). */
interface CarSource {
    val id: String
    /** True when buttons in the UI may change state (demo only — the Mégane is never actuated). */
    val interactive: Boolean
    fun start(hub: CarHub)
    fun stop()
}

/** Holds the merged car state and the active source. */
class CarHub(private val ctx: Context) {
    private val _state = MutableStateFlow(CarState())
    val state: StateFlow<CarState> = _state.asStateFlow()

    /** Raw values for the "CAN live view" debug screen. */
    private val _raw = MutableStateFlow<Map<String, String>>(emptyMap())
    val raw: StateFlow<Map<String, String>> = _raw.asStateFlow()

    private val _sourceId = MutableStateFlow("none")
    val sourceId: StateFlow<String> = _sourceId.asStateFlow()

    var source: CarSource? = null
        private set

    /** When the last vendor/CAN broadcast arrived (capability detection + diagnostics). */
    val lastVendorMessageAt = MutableStateFlow(0L)

    val demo: DemoCarSource? get() = source as? DemoCarSource

    fun use(kind: String, vendorActions: String, vendorMap: String = "") {
        source?.stop()
        _state.value = CarState()
        _raw.value = emptyMap()
        val s = when (kind) {
            "demo" -> DemoCarSource()
            "vendor" -> VendorCarSource(ctx, vendorActions, vendorMap)
            else -> NoCarSource()
        }
        source = s
        _sourceId.value = s.id
        s.start(this)
    }

    fun update(f: (CarState) -> CarState) = _state.update(f)
    fun raw(key: String, value: String) = _raw.update { it + (key to value) }

    /** GPS speed is used whenever the car source has no speed of its own. */
    fun gpsSpeed(kmh: Float) {
        if (source !is DemoCarSource) raw("gps.speed", "%.1f".format(kmh))
    }
}

/** Fully simulated car for testing on a tablet. Driven by the navigation simulator. */
class DemoCarSource : CarSource {
    override val id = "demo"
    override val interactive = true
    private var hub: CarHub? = null

    override fun start(hub: CarHub) {
        this.hub = hub
        hub.update { CarState(gear = Gear.P, speedKmh = 0f, headlights = false, locked = true, outsideTempC = 18f, passengerAirbagOff = false) }
        log()
    }

    override fun stop() { hub = null }

    private fun log() {
        val h = hub ?: return
        val s = h.state.value
        h.raw("gear", s.gear?.name ?: "-")
        h.raw("speed_kmh", "%.1f".format(s.speedKmh ?: 0f))
        h.raw("headlights", s.headlights.toString())
        h.raw("brake", s.braking.toString())
        h.raw("locked", s.locked.toString())
        h.raw("doors", s.doors.run { "FL=$fl FR=$fr RL=$rl RR=$rr trunk=$trunk hood=$hood" })
        h.raw("outside_temp", (s.outsideTempC ?: 0f).toString())
    }

    private fun upd(f: (CarState) -> CarState) { hub?.update(f); log() }

    fun setGear(g: Gear) = upd { s ->
        // Leaving P closes the doors, like a real car's interlock would make you do.
        if (g != Gear.P) s.copy(gear = g, doors = Doors(), locked = true) else s.copy(gear = g, speedKmh = 0f)
    }
    fun setMotion(kmh: Float, braking: Boolean) = upd { it.copy(speedKmh = kmh, braking = braking) }
    fun setHeadlights(on: Boolean) = upd { it.copy(headlights = on) }
    fun toggleLock() = upd { it.copy(locked = !(it.locked ?: true)) }
    fun toggleTrunk() = upd { if (it.gear == Gear.P || it.gear == null) it.copy(doors = it.doors.copy(trunk = !it.doors.trunk)) else it }
    fun toggleHood() = upd { if (it.gear == Gear.P || it.gear == null) it.copy(doors = it.doors.copy(hood = !it.doors.hood)) else it }
    fun toggleDoor(which: String) = upd {
        if (it.gear != Gear.P) return@upd it
        val d = it.doors
        it.copy(doors = when (which) {
            "fl" -> d.copy(fl = !d.fl); "fr" -> d.copy(fr = !d.fr)
            "rl" -> d.copy(rl = !d.rl); "rr" -> d.copy(rr = !d.rr)
            else -> d
        })
    }
    fun setOutsideTemp(c: Float) = upd { it.copy(outsideTempC = c) }
}

/** No car data: speed comes from GPS, car is shown parked. */
class NoCarSource : CarSource {
    override val id = "none"
    override val interactive = false
    override fun start(hub: CarHub) {}
    override fun stop() {}
}

/**
 * Car data from the head unit's own firmware (Junsun/FYT/… MCU + canbox): broadcasts and
 * Android settings, mapped by [VendorRule]s (discovered with tools/headunit, or pushed from a
 * laptop). Actions without a rule are still logged in Diagnostics and parsed heuristically.
 */
class VendorCarSource(private val ctx: Context, actionsCsv: String, mapJson: String = "") : CarSource {
    override val id = "vendor"
    override val interactive = false
    private var hub: CarHub? = null
    private val rules = VendorMap.parse(mapJson)
    private val actions = (actionsCsv.split(',', ';', '\n').map { it.trim() } + rules.mapNotNull { it.action })
        .filter { it.isNotEmpty() }.distinct()
    private val settingRules = rules.filter { it.setting != null }
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            val h = hub ?: return
            intent ?: return
            h.lastVendorMessageAt.value = System.currentTimeMillis()
            val extras = intent.extras
            val keys = extras?.keySet().orEmpty()
            @Suppress("DEPRECATION")
            h.raw(intent.action ?: "?", keys.joinToString { k -> "$k=${extras?.get(k)}" }.ifEmpty { "(no extras)" })
            // One parseable line per broadcast — tools/headunit reads values from here.
            @Suppress("DEPRECATION")
            Log.i("OhmVendor", "${intent.action} | " + keys.sorted().joinToString("; ") { k -> "$k=${extras?.get(k)}" })
            val matching = rules.filter { it.action == intent.action }
            if (matching.isNotEmpty()) {
                for (r in matching) {
                    @Suppress("DEPRECATION")
                    val raw = (if (r.extra != null) extras?.get(r.extra)?.toString() else null) ?: r.value ?: if (r.extra == null) "1" else continue
                    h.update { VendorMap.apply(r, raw, it) }
                }
                return
            }
            heuristics(h, extras, keys)
        }
    }

    @Suppress("DEPRECATION")
    private fun heuristics(h: CarHub, extras: android.os.Bundle?, keys: Set<String>) {
        for (k in keys) {
            val v = extras?.get(k)
            val lk = k.lowercase()
            when {
                "gear" in lk -> parseGear(v)?.let { g -> h.update { it.copy(gear = g) } }
                "reverse" in lk || "back_car" in lk -> asBool(v)?.let { r -> h.update { it.copy(gear = if (r) Gear.R else (it.gear.takeIf { g -> g != Gear.R } ?: Gear.D)) } }
                "speed" in lk -> (v as? Number)?.toFloat()?.let { s -> h.update { it.copy(speedKmh = s) } }
                "ill" in lk || "headlight" in lk || "lamp" in lk || "night" in lk -> asBool(v)?.let { on -> h.update { it.copy(headlights = on) } }
                "door" in lk -> asBool(v)?.let { open -> h.update { it.copy(doors = it.doors.copy(fl = open)) } }
                "temp" in lk && "out" in lk -> (v as? Number)?.toFloat()?.let { t -> h.update { it.copy(outsideTempC = t) } }
            }
        }
    }

    private fun asBool(v: Any?): Boolean? = when (v) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is String -> v == "1" || v.equals("true", true) || v.equals("on", true)
        else -> null
    }

    private fun parseGear(v: Any?): Gear? = when (v.toString().uppercase()) {
        "P", "0" -> Gear.P; "R", "1" -> Gear.R; "N", "2" -> Gear.N; "D", "3" -> Gear.D
        else -> null
    }

    // ---- settings-based rules (some firmwares write states like the illumination wire to Settings)

    private val settingsObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = readSettings()
    }

    private fun readSetting(spec: String): String? {
        val (table, key) = spec.split('/', limit = 2).let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        val cr = ctx.contentResolver
        return runCatching {
            when (table) {
                "global" -> android.provider.Settings.Global.getString(cr, key)
                "secure" -> android.provider.Settings.Secure.getString(cr, key)
                else -> android.provider.Settings.System.getString(cr, key)
            }
        }.getOrNull()
    }

    private fun readSettings() {
        val h = hub ?: return
        for (r in settingRules) {
            val raw = readSetting(r.setting!!) ?: continue
            h.raw("setting:${r.setting}", raw)
            h.update { VendorMap.apply(r, raw, it) }
        }
    }

    override fun start(hub: CarHub) {
        this.hub = hub
        hub.raw("status", when {
            actions.isEmpty() && settingRules.isEmpty() -> "No vendor mapping yet — run tools/headunit on a laptop, or add actions in Controls › Vehicle"
            else -> "Listening: ${actions.size} broadcast(s), ${settingRules.size} setting(s), ${rules.size} rule(s)"
        })
        if (actions.isNotEmpty()) {
            val f = IntentFilter().apply { actions.forEach { addAction(it) } }
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") ctx.registerReceiver(receiver, f)
            registered = true
        }
        if (settingRules.isNotEmpty()) {
            for (t in listOf(android.provider.Settings.System.CONTENT_URI, android.provider.Settings.Global.CONTENT_URI, android.provider.Settings.Secure.CONTENT_URI))
                ctx.contentResolver.registerContentObserver(t, true, settingsObserver)
            readSettings()
        }
    }

    override fun stop() {
        if (registered) runCatching { ctx.unregisterReceiver(receiver) }
        registered = false
        runCatching { ctx.contentResolver.unregisterContentObserver(settingsObserver) }
        hub = null
    }
}
