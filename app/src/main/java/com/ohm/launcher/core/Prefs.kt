package com.ohm.launcher.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** A single persisted setting exposed as a StateFlow. */
class Pref<T>(
    private val sp: SharedPreferences,
    val key: String,
    val default: T,
    private val read: SharedPreferences.(String, T) -> T,
    private val write: SharedPreferences.Editor.(String, T) -> Unit,
) {
    private val _state = MutableStateFlow(sp.read(key, default))
    val state: StateFlow<T> = _state.asStateFlow()
    val value: T get() = _state.value
    fun set(v: T) {
        _state.value = v
        sp.edit().apply { write(key, v) }.apply()
    }

    /** Used by vehicle profiles to store / restore this setting. */
    fun putTo(o: JSONObject) { o.put(key, value) }

    @Suppress("UNCHECKED_CAST")
    fun loadFrom(o: JSONObject?) {
        val v: Any? = when (val d = default) {
            is Boolean -> o?.optBoolean(key, d) ?: d
            is Int -> o?.optInt(key, d) ?: d
            is Float -> o?.optDouble(key, d.toDouble())?.toFloat() ?: d
            is String -> o?.optString(key, d) ?: d
            else -> d
        }
        set(v as T)
    }
}

/**
 * Settings, split into
 *  - global preferences (units, language, theme, navigation provider, look & feel), and
 *  - vehicle settings ([vehicleScoped]) that are swapped when the active vehicle profile changes.
 */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("ohm", Context.MODE_PRIVATE)

    private fun str(k: String, d: String) = Pref(sp, k, d, { key, def -> getString(key, def) ?: def }, { key, v -> putString(key, v) })
    private fun bool(k: String, d: Boolean) = Pref(sp, k, d, { key, def -> getBoolean(key, def) }, { key, v -> putBoolean(key, v) })
    private fun int(k: String, d: Int) = Pref(sp, k, d, { key, def -> getInt(key, def) }, { key, v -> putInt(key, v) })
    private fun float(k: String, d: Float) = Pref(sp, k, d, { key, def -> getFloat(key, def) }, { key, v -> putFloat(key, v) })
    private fun list(k: String) = Pref(sp, k, emptyList<String>(),
        { key, def -> getString(key, null)?.split('|')?.filter { it.isNotBlank() } ?: def },
        { key, v -> putString(key, v.joinToString("|")) })

    // ============================================================ global

    val onboardingDone = bool("onboardingDone", false)
    /** en | pt */
    val language = str("language", if (java.util.Locale.getDefault().language == "pt") "pt" else "en")

    // Units (regional preferences)
    val distanceKm = bool("distanceKm", true)
    val speedKmh = bool("speedKmh", true)
    val tempC = bool("tempC", true)
    /** l100 | kml | mpgus | mpguk */
    val fuelUnit = str("fuelUnit", "l100")
    /** bar | psi | kpa */
    val pressureUnit = str("pressureUnit", "bar")
    /** ISO code */
    val currency = str("currency", "EUR")
    /** Fuel price per litre, in [currency]. 0 = unknown. */
    val fuelPrice = float("fuelPrice", 0f)

    // Appearance
    /** auto_lights | auto_time | light | dark */
    val themeMode = str("themeMode", "auto_lights")
    val accent = int("accent", 0)
    /** full | reduced | off */
    val motion = str("motion", "full")
    /** standard | map */
    val layout = str("layout", "standard")
    val panelFraction = float("panelFraction", 0.35f)
    /** auto | on | off */
    val lowPower = str("lowPower", "auto")
    val hideSystemBars = bool("hideSystemBars", true)
    val systemDarkSync = bool("systemDarkSync", false)
    val showMusicWidget = bool("showMusicWidget", true)
    val showSpeedLimit = bool("showSpeedLimit", true)
    val showWeather = bool("showWeather", true)
    val showCarButtons = bool("showCarButtons", true)
    val contextualDock = bool("contextualDock", true)
    /** Diagnostics: also show options the hardware doesn't seem to support. */
    val showAllOptions = bool("showAllOptions", false)

    // Map & navigation
    /** auto | light | dark | satellite */
    val mapStyle = str("mapStyle", "auto")
    val headingUp = bool("headingUp", true)
    val buildings3d = bool("buildings3d", true)
    val voice = bool("voice", true)
    val speedCameras = bool("speedCameras", true)
    val avoidTolls = bool("avoidTolls", false)
    val avoidHighways = bool("avoidHighways", false)
    /** internal | google | waze | other */
    val navApp = str("navApp", "internal")
    val home = str("home", "")
    val work = str("work", "")
    val recentPlaces = list("recentPlaces")

    // Location
    /** auto | unit | companion | external */
    val locationProvider = str("locationProvider", "auto")
    val companionCode = str("companionCode", (1000..9999).random().toString())
    val externalGpsPort = int("externalGpsPort", 10110)

    // Media
    /** auto | standalone | phone */
    val mediaMode = str("mediaMode", "auto")
    val demoMedia = bool("demoMedia", true)
    // Spotify (Web API, user's own developer app)
    val spotifyClientId = str("spotifyClientId", "")
    /** Keystore-encrypted token blob. */
    val spotifyTokens = str("spotifyTokens", "")
    /** Preferred Spotify Connect device name ("" = this unit / whatever is active). */
    val spotifyDevice = str("spotifyDevice", "")
    /** Name this unit's Spotify app uses as a Connect device (learned automatically). */
    val spotifyLocalName = str("spotifyLocalName", "")
    val lyrics = bool("lyrics", true)
    /** Dock/drawer Spotify icon opens Ohm's Spotify screen instead of the app. */
    val spotifyInOhm = bool("spotifyInOhm", true)
    /** Built-in librespot Connect receiver (unofficial, off by default). */
    val librespot = bool("librespot", false)
    val librespotName = str("librespotName", "Ohm (car)")
    val librespotBitrate = int("librespotBitrate", 320)
    val volumeStep = int("volumeStep", 5)
    /** Speed-sensitive volume: 0 off, 1 low, 2 medium, 3 high. */
    val speedVolume = int("speedVolume", 0)
    /** Equalizer for the built-in Spotify receiver: on/off + 5 band gains in dB (-10..10). */
    val eqOn = bool("eqOn", false)
    val eqBands = str("eqBands", "0,0,0,0,0")
    // Driving lockout
    val lockout = bool("lockout", true)
    val lockoutSpeed = int("lockoutSpeed", 8)
    /** Apps the user allowed while driving (overrides the video/game/browser block). */
    val lockoutAllowed = list("lockoutAllowed")
    // In-app updates
    val updateUrl = str("updateUrl", "")

    // Apps
    val dockApps = list("dockApps")
    val dockInitialized = bool("dockInitialized", false)
    val hiddenApps = list("hiddenApps")
    val recentApps = list("recentApps")

    // Data
    val weatherCache = str("weatherCache", "")
    val trips = str("trips", "[]")
    val profiles = str("profiles", "")
    val activeProfile = str("activeProfile", "")

    // ============================================================ vehicle-scoped

    val vehicleName = str("vehicleName", "My car")
    /** demo | vendor | none */
    val carSource = str("carSource", "demo")
    val vendorActions = str("vendorActions", "")
    /** Mapping rules (JSON, see car/VendorMap.kt) — usually pushed from tools/headunit. */
    val vendorMap = str("vendorMap", "")
    /** hatch | estate | coupe */
    val carBody = str("carBody", "hatch")
    val carColor = int("carColor", 0)
    val rhd = bool("rhd", false)
    /** Typical consumption in L/100 km, 0 = unknown (used for trip estimates). */
    val consumption = float("consumption", 0f)
    // Reverse camera (Ohm's own behaviour only — never the unit's firmware camera switching)
    val cameraAuto = bool("cameraAuto", true)
    val cameraApp = str("cameraApp", "")
    /** can | mcu | broadcast */
    val cameraTrigger = str("cameraTrigger", "can")
    val cameraAction = str("cameraAction", "")
    val cameraExtra = str("cameraExtra", "")
    val cameraReturn = bool("cameraReturn", true)
    // OBD-II adapter (ELM327 over Bluetooth)
    val obdEnabled = bool("obdEnabled", false)
    val obdDevice = str("obdDevice", "")
    val obdDeviceName = str("obdDeviceName", "")
    /** petrol | diesel | lpg — for fuel-flow maths when the car doesn't report it directly. */
    val fuelType = str("fuelType", "petrol")

    val vehicleScoped: List<Pref<*>> = listOf(
        vehicleName, carSource, vendorActions, vendorMap, carBody, carColor, rhd, consumption,
        cameraAuto, cameraApp, cameraTrigger, cameraAction, cameraExtra, cameraReturn,
        obdEnabled, obdDevice, obdDeviceName, fuelType,
    )
}
