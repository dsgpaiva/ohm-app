package com.ohm.launcher.core

import android.app.UiModeManager
import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.ohm.launcher.Ohm
import com.ohm.launcher.car.Gear
import com.ohm.launcher.i18n.Strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

// ===================================================================== Weather

data class HourWx(val hour: String, val tempC: Double, val code: Int, val rainPct: Int)
data class DayWx(val day: String, val code: Int, val max: Double, val min: Double, val rainPct: Int, val uv: Double)
data class Weather(
    val tempC: Double, val code: Int, val isDay: Boolean, val humidity: Int, val windKmh: Double,
    val hourly: List<HourWx>, val daily: List<DayWx>, val aqi: Int?, val place: String?, val fetchedAt: Long,
)

/** Open-Meteo — no API key needed. Cached so the last data shows offline. */
class WeatherRepo(private val scope: CoroutineScope) {
    private val _s = MutableStateFlow<Weather?>(null)
    val state: StateFlow<Weather?> = _s.asStateFlow()
    private var lastPos: LatLon? = null
    private var lastFetch = 0L

    fun init() {
        Ohm.prefs.weatherCache.value.takeIf { it.isNotEmpty() }?.let { runCatching { _s.value = parse(JSONObject(it), null, null) } }
        scope.launch {
            while (isActive) {
                val p = Ohm.nav.state.value.pos
                val moved = lastPos?.let { GeoMath.dist(it, p) > 5000 } ?: true
                if (moved || System.currentTimeMillis() - lastFetch > 15 * 60_000) refresh(p)
                delay(60_000)
            }
        }
    }

    suspend fun refresh(p: LatLon) {
        runCatching {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=${p.lat}&longitude=${p.lon}" +
                "&current=temperature_2m,weather_code,relative_humidity_2m,wind_speed_10m,is_day" +
                "&hourly=temperature_2m,weather_code,precipitation_probability" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,uv_index_max" +
                "&timezone=auto&forecast_days=7"
            val raw = Http.get(url)
            val aqi = runCatching {
                JSONObject(Http.get("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=${p.lat}&longitude=${p.lon}&current=european_aqi"))
                    .getJSONObject("current").optInt("european_aqi")
            }.getOrNull()
            val place = runCatching { com.ohm.launcher.nav.Geo.reverse(p) }.getOrNull()
            val o = JSONObject(raw)
            _s.value = parse(o, aqi, place)
            Ohm.prefs.weatherCache.set(raw)
            lastPos = p; lastFetch = System.currentTimeMillis()
            if (Ohm.car.demo != null) Ohm.car.demo?.setOutsideTemp(_s.value!!.tempC.toFloat())
        }
    }

    private fun parse(o: JSONObject, aqi: Int?, place: String?): Weather {
        val c = o.getJSONObject("current")
        val h = o.getJSONObject("hourly")
        val ht = h.getJSONArray("time"); val htemp = h.getJSONArray("temperature_2m")
        val hcode = h.getJSONArray("weather_code"); val hrain = h.getJSONArray("precipitation_probability")
        val nowIso = c.getString("time").take(13)
        var start = (0 until ht.length()).indexOfFirst { ht.getString(it).take(13) >= nowIso }.coerceAtLeast(0)
        val hourly = (start until minOf(start + 12, ht.length())).map {
            HourWx(ht.getString(it).substring(11, 16), htemp.getDouble(it), hcode.getInt(it), hrain.optInt(it))
        }
        val d = o.getJSONObject("daily")
        val dt = d.getJSONArray("time")
        val daily = (0 until dt.length()).map {
            DayWx(dt.getString(it), d.getJSONArray("weather_code").getInt(it), d.getJSONArray("temperature_2m_max").getDouble(it),
                d.getJSONArray("temperature_2m_min").getDouble(it), d.getJSONArray("precipitation_probability_max").optInt(it),
                d.getJSONArray("uv_index_max").optDouble(it, 0.0))
        }
        return Weather(c.getDouble("temperature_2m"), c.getInt("weather_code"), c.optInt("is_day", 1) == 1,
            c.optInt("relative_humidity_2m"), c.optDouble("wind_speed_10m"), hourly, daily, aqi, place ?: _s.value?.place, System.currentTimeMillis())
    }
}

// ===================================================================== Day / night

/** Sunrise/sunset (NOAA simplified). Returns hours in local time, or null for polar day/night. */
object Sun {
    fun riseSet(lat: Double, lon: Double, cal: Calendar = Calendar.getInstance()): Pair<Double, Double>? {
        val n = cal.get(Calendar.DAY_OF_YEAR)
        val tz = TimeZone.getDefault().getOffset(cal.timeInMillis) / 3_600_000.0
        val g = 2 * Math.PI / 365 * (n - 1)
        val eq = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) - 0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val decl = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) + 0.000907 * sin(2 * g)
        val latR = Math.toRadians(lat)
        val cosH = (cos(Math.toRadians(90.833)) / (cos(latR) * cos(decl))) - tan(latR) * tan(decl)
        if (cosH < -1 || cosH > 1) return null
        val ha = Math.toDegrees(acos(cosH))
        val rise = (720 - 4 * (lon + ha) - eq) / 60 + tz
        val set = (720 - 4 * (lon - ha) - eq) / 60 + tz
        return rise to set
    }

    fun isNight(lat: Double, lon: Double): Boolean {
        val c = Calendar.getInstance()
        val h = c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60.0
        val rs = riseSet(lat, lon, c) ?: return (h < 7 || h > 20)
        return h < rs.first || h > rs.second
    }

    @Suppress("unused") private fun unused() = asin(0.0)
}

/** Decides light/dark (SPEC.md §7): CAN headlights → ILL → sun position, with 10 s hysteresis. */
class DayNight(private val ctx: Context, private val scope: CoroutineScope) {
    private val _dark = MutableStateFlow(true)
    val dark: StateFlow<Boolean> = _dark.asStateFlow()
    private val _reason = MutableStateFlow("")
    val reason: StateFlow<String> = _reason.asStateFlow()
    private var pending: Job? = null
    private var first = true
    private val _sunTick = MutableStateFlow(0)

    fun init() {
        scope.launch { while (isActive) { delay(60_000); _sunTick.update { it + 1 } } }
        scope.launch {
            combine(Ohm.prefs.themeMode.state, Ohm.car.state, Ohm.nav.state, _sunTick) { mode, car, nav, _ ->
                val sunNight = Sun.isNight(nav.pos.lat, nav.pos.lon)
                when (mode) {
                    "light" -> false to "Manual"
                    "dark" -> true to "Manual"
                    "auto_time" -> sunNight to "Sunset"
                    else -> (car.headlights ?: sunNight) to (if (car.headlights != null) "Headlights" else "Sunset")
                }
            }.collect { (d, why) ->
                _reason.value = why
                if (first || why == "Manual") { apply(d); first = false; return@collect }
                if (d == _dark.value) { pending?.cancel(); return@collect }
                if (pending?.isActive == true) return@collect
                pending = scope.launch { delay(10_000); apply(d) }
            }
        }
    }

    private fun apply(d: Boolean) {
        _dark.value = d
        if (Ohm.prefs.systemDarkSync.value) {
            runCatching {
                ctx.getSystemService(UiModeManager::class.java)
                    .setNightMode(if (d) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO)
            }
        }
    }
}

// ===================================================================== Volume & connectivity

class SystemRepo(private val ctx: Context) {
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val _volume = MutableStateFlow(0f)
    val volume: StateFlow<Float> = _volume.asStateFlow()
    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()
    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online.asStateFlow()
    private val _wifi = MutableStateFlow(false)
    val wifi: StateFlow<Boolean> = _wifi.asStateFlow()

    private fun max() = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    fun init() {
        readVolume(false)
        ctx.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = readVolume(true)
        })
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        _online.value = cm.activeNetwork != null
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { _online.value = true }
            override fun onLost(network: Network) { _online.value = cm.activeNetwork != null && cm.activeNetwork != network }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                _wifi.value = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            }
        })
    }

    /** Automatic changes (speed-sensitive volume) don't show the volume banner. */
    @Volatile var quietUntil = 0L

    private fun readVolume(notify: Boolean) {
        val v = am.getStreamVolume(AudioManager.STREAM_MUSIC) / max().toFloat()
        val m = am.isStreamMute(AudioManager.STREAM_MUSIC)
        val changed = v != _volume.value || m != _muted.value
        _volume.value = v; _muted.value = m
        if (notify && changed && System.currentTimeMillis() > quietUntil) Ohm.banners.volume()
    }

    fun step(up: Boolean) {
        val stepPct = Ohm.prefs.volumeStep.value.coerceIn(1, 25) / 100f
        val target = ((_volume.value + if (up) stepPct else -stepPct).coerceIn(0f, 1f) * max()).let { Math.round(it) }
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val idx = if (target == cur) (cur + if (up) 1 else -1).coerceIn(0, max()) else target
        if (_muted.value && up) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, idx, 0)
        readVolume(true)
    }

    fun toggleMute() {
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0)
        readVolume(true)
    }
}

// ===================================================================== Banners

data class BannerAction(val label: String, val primary: Boolean = false, val danger: Boolean = false, val onClick: () -> Unit)

data class Banner(
    val id: Long,
    /** call | message | camera | info | offline */
    val kind: String,
    val title: String,
    val text: String,
    val actions: List<BannerAction> = emptyList(),
    val sticky: Boolean = false,
    val startedAt: Long = System.currentTimeMillis(),
)

/** In-app notification stack shown above the music widget (SPEC.md §5.1). */
class Banners(private val scope: CoroutineScope) {
    private val _list = MutableStateFlow<List<Banner>>(emptyList())
    val list: StateFlow<List<Banner>> = _list.asStateFlow()
    private val _volumeShownAt = MutableStateFlow(0L)
    val volumeShownAt: StateFlow<Long> = _volumeShownAt.asStateFlow()
    private var ids = 1L

    fun volume() { _volumeShownAt.value = System.currentTimeMillis() }

    fun push(b: Banner, timeoutMs: Long = 6000): Long {
        val id = ids++
        _list.update { (it.filterNot { o -> o.kind == b.kind && b.kind in setOf("offline", "camera") } + b.copy(id = id)).takeLast(4) }
        if (!b.sticky) scope.launch { delay(timeoutMs); dismiss(id) }
        return id
    }

    fun dismiss(id: Long) = _list.update { l -> l.filterNot { it.id == id } }
    fun dismissKind(kind: String) = _list.update { l -> l.filterNot { it.kind == kind } }
    fun replace(id: Long, f: (Banner) -> Banner) = _list.update { l -> l.map { if (it.id == id) f(it) else it } }

    fun info(title: String, text: String) = push(Banner(0, "info", title, text))

    /** Relayed by Ohm Companion from the phone. */
    fun phoneNotification(app: String, title: String, text: String) =
        push(Banner(0, "message", title.ifEmpty { app }, if (app.isNotEmpty() && title.isNotEmpty()) "$app · $text" else text), 8000)

    private fun tr(k: String) = Strings.tr(Ohm.prefs.language.value, k)

    // ---- demo events (Settings › System › Demo events)

    fun demoCall() {
        var id = 0L
        id = push(Banner(0, "call", "Ana Silva", tr("Incoming call"), sticky = true, actions = listOf(
            BannerAction(tr("Decline"), danger = true) { dismiss(id) },
            BannerAction(tr("Answer"), primary = true) {
                replace(id) { it.copy(text = tr("Ongoing call"), startedAt = System.currentTimeMillis(), actions = listOf(
                    BannerAction(tr("Mute")) {},
                    BannerAction(tr("End"), danger = true) { dismiss(id) },
                )) }
            },
        )))
    }

    fun demoMessage() = push(Banner(0, "message", "Rui", tr("On my way, see you in 10 min 👍"), actions = listOf(
        BannerAction(tr("Reply \"OK\""), primary = true) {},
    )), 9000)

    fun watchConnectivity(sys: SystemRepo) {
        scope.launch {
            sys.online.collect { on ->
                if (!on) push(Banner(0, "offline", tr("No connection"), tr("Maps and weather will use cached data"), sticky = true))
                else dismissKind("offline")
            }
        }
    }

    fun watchReverse() {
        scope.launch {
            Ohm.car.state.collect { s -> if (s.gear == Gear.R) dismissKind("message") }
        }
    }
}
