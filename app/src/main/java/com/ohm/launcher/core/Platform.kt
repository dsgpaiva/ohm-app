package com.ohm.launcher.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

// ===================================================================== log

/** In-memory app log shown in Diagnostics (also forwarded to logcat). */
object OhmLog {
    private const val MAX = 300
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun i(tag: String, msg: String) = add("I", tag, msg)
    fun w(tag: String, msg: String, t: Throwable? = null) = add("W", tag, msg + (t?.let { " — ${it.javaClass.simpleName}: ${it.message}" } ?: ""))

    @Synchronized
    private fun add(level: String, tag: String, msg: String) {
        if (level == "W") Log.w("Ohm/$tag", msg) else Log.i("Ohm/$tag", msg)
        _lines.value = (_lines.value + "${fmt.format(Date())} $level $tag: $msg").takeLast(MAX)
    }

    fun text() = _lines.value.joinToString("\n")
}

// ===================================================================== performance

object Perf {
    /** Average frame time (ms) of the last ~60 rendered frames of the launcher UI. */
    val frameMs = MutableStateFlow(0.0)
    val slow = MutableStateFlow(false)
}

// ===================================================================== capabilities

/**
 * What the hardware/software can do — kept separate from what the user chose to enable
 * (SPEC-ideas §4). Settings only offer options whose capability is present, unless
 * "Show unsupported options" is on in Diagnostics.
 */
data class Capabilities(
    val gpsHardware: Boolean = false,
    val locationPermission: Boolean = false,
    val bluetooth: Boolean = false,
    /** Vendor / canbox / MCU service packages found on the unit. */
    val vendorPackages: List<String> = emptyList(),
    /** Vendor car data actually received recently. */
    val canReceiving: Boolean = false,
    val cameraApps: List<String> = emptyList(),
    val mediaAccess: Boolean = false,
    val googleMaps: Boolean = false,
    val waze: Boolean = false,
    val companionSeen: Boolean = false,
    val externalGpsSeen: Boolean = false,
) {
    val canPossible get() = vendorPackages.isNotEmpty() || canReceiving
    val camera get() = cameraApps.isNotEmpty()
}

class CapabilityDetector(private val ctx: Context, private val scope: CoroutineScope) {
    private val _caps = MutableStateFlow(Capabilities())
    val caps: StateFlow<Capabilities> = _caps.asStateFlow()

    /** Package-name fragments used by Chinese head-unit firmwares (FYT/SYU, Microntek, TopWay, Junsun…). */
    private val vendorHints = listOf("canbus", "mcu", "syu", "microntek", "fyt", "topway", "junsun", "carsetting", "car.service", "can.service", "hct", "xygala")
    private val cameraHints = listOf("camera", "avm", "dvr", "reverse", "backcar", "rearview", "360")

    fun init() {
        scope.launch {
            while (isActive) { refresh(); delay(15_000) }
        }
    }

    fun refresh() {
        val pm = ctx.packageManager
        val installed = runCatching { pm.getInstalledPackages(0).map { it.packageName } }.getOrDefault(emptyList())
        val launchable = com.ohm.launcher.Ohm.apps.apps.value
        val loc = com.ohm.launcher.Ohm.location
        _caps.value = Capabilities(
            gpsHardware = pm.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS),
            locationPermission = com.ohm.launcher.Ohm.nav.hasLocationPermission(),
            bluetooth = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH),
            vendorPackages = installed.filter { p -> vendorHints.any { p.lowercase().contains(it) } }.sorted(),
            canReceiving = com.ohm.launcher.Ohm.car.lastVendorMessageAt.value.let { it > 0 && System.currentTimeMillis() - it < 60_000 },
            cameraApps = launchable.filter { a -> cameraHints.any { a.pkg.lowercase().contains(it) || a.label.lowercase().contains(it) } }.map { it.pkg },
            mediaAccess = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName),
            googleMaps = installed.contains("com.google.android.apps.maps"),
            waze = installed.contains("com.waze"),
            companionSeen = loc.lastFix("companion") != null,
            externalGpsSeen = loc.lastFix("external") != null,
        )
    }

    fun canHandle(i: Intent) = runCatching { ctx.packageManager.resolveActivity(i, 0) != null }.getOrDefault(false)
}

// ===================================================================== vehicle profiles

data class VehicleRef(val id: String, val name: String)

/**
 * Multiple vehicles, each with its own CAN source, model, paint, driving position, camera setup…
 * The vehicle-scoped [Prefs] always hold the *active* vehicle; switching saves them into the
 * old profile and loads the new one.
 */
class Profiles(private val prefs: Prefs) {
    private val _list = MutableStateFlow<List<VehicleRef>>(emptyList())
    val list: StateFlow<List<VehicleRef>> = _list.asStateFlow()
    private val _active = MutableStateFlow("")
    val active: StateFlow<String> = _active.asStateFlow()

    private fun all(): JSONObject = runCatching { JSONObject(prefs.profiles.value) }.getOrDefault(JSONObject())

    fun init() {
        val o = all()
        if (o.length() == 0) {
            val id = UUID.randomUUID().toString().take(8)
            o.put(id, snapshot())
            prefs.profiles.set(o.toString())
            prefs.activeProfile.set(id)
        }
        if (!o.has(prefs.activeProfile.value)) prefs.activeProfile.set(o.keys().next())
        _active.value = prefs.activeProfile.value
        publish()
    }

    private fun snapshot(): JSONObject = JSONObject().also { j -> prefs.vehicleScoped.forEach { it.putTo(j) } }

    private fun publish() {
        val o = all()
        _list.value = o.keys().asSequence().map { VehicleRef(it, o.getJSONObject(it).optString(prefs.vehicleName.key, "Car")) }.toList()
    }

    /** Persist the active vehicle's current settings (called whenever a vehicle setting changes). */
    fun save() {
        val o = all()
        o.put(_active.value, snapshot())
        prefs.profiles.set(o.toString())
        publish()
    }

    fun switchTo(id: String) {
        if (id == _active.value) return
        save()
        val o = all()
        val target = o.optJSONObject(id) ?: return
        prefs.vehicleScoped.forEach { it.loadFrom(target) }
        prefs.activeProfile.set(id)
        _active.value = id
        OhmLog.i("Profiles", "Switched to vehicle ${prefs.vehicleName.value}")
        publish()
    }

    fun add(name: String): String {
        save()
        val id = UUID.randomUUID().toString().take(8)
        prefs.vehicleScoped.forEach { it.loadFrom(null) } // defaults
        prefs.vehicleName.set(name)
        prefs.activeProfile.set(id)
        _active.value = id
        save()
        return id
    }

    fun rename(name: String) { prefs.vehicleName.set(name); save() }

    fun delete(id: String) {
        val o = all()
        if (o.length() <= 1) return
        o.remove(id)
        prefs.profiles.set(o.toString())
        if (id == _active.value) {
            _active.value = ""
            val next = o.keys().next()
            prefs.vehicleScoped.forEach { it.loadFrom(o.getJSONObject(next)) }
            prefs.activeProfile.set(next)
            _active.value = next
        }
        publish()
    }

    @Suppress("unused") private fun unused() = JSONArray()
}
