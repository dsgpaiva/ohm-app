package com.ohm.launcher.nav

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.speech.tts.TextToSpeech
import com.ohm.launcher.Ohm
import com.ohm.launcher.car.Gear
import com.ohm.launcher.core.GeoMath
import com.ohm.launcher.core.LatLon
import com.ohm.launcher.i18n.Strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class NavState(
    val pos: LatLon = DEMO_START,
    val bearing: Double = 0.0,
    val speedKmh: Float = 0f,
    val hasFix: Boolean = false,
    val simulated: Boolean = true,
    val destination: Place? = null,
    val previews: List<Route> = emptyList(),
    val selected: Int = 0,
    val loadingRoute: Boolean = false,
    val error: String? = null,
    val route: Route? = null,
    val navigating: Boolean = false,
    val progressM: Double = 0.0,
    val stepIdx: Int = 0,
    val distToStepM: Double = 0.0,
    val remainingM: Double = 0.0,
    val remainingS: Double = 0.0,
    val speedLimit: Int? = null,
    val camera: SpeedCamera? = null,
    val cameraDistM: Double = 0.0,
    val arrived: Boolean = false,
) {
    val nextStep: Step? get() = route?.steps?.getOrNull(stepIdx)
    val followingStep: Step? get() = route?.steps?.getOrNull(stepIdx + 1)

    companion object {
        /** Praça do Comércio, Lisboa — demo starting point. */
        val DEMO_START = LatLon(38.70755, -9.13650)
    }
}

class NavController(private val ctx: Context, private val scope: CoroutineScope) {
    private val _s = MutableStateFlow(NavState())
    val state: StateFlow<NavState> = _s.asStateFlow()

    private var simJob: Job? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val announced = HashSet<String>()
    private var offRouteCount = 0
    fun init() {
        tts = TextToSpeech(ctx) { status -> ttsReady = status == TextToSpeech.SUCCESS }
        scope.launch { Ohm.prefs.carSource.state.collect { setSimulated(it == "demo") } }
        Ohm.location.onFix = { onFix(it) }
        // GPS gone quiet (route ended, tunnel, receiver lost): don't freeze the last speed.
        Ohm.location.onStale = {
            if (simJob?.isActive != true && _s.value.speedKmh > 0) {
                _s.update { it.copy(speedKmh = 0f) }
                Ohm.car.gpsSpeed(0f)
                if (_s.value.simulated) Ohm.car.demo?.setMotion(0f, false)
            }
        }
    }

    private fun setSimulated(sim: Boolean) {
        _s.update { it.copy(simulated = sim) }
        if (!sim) { simJob?.cancel(); simJob = null }
        else if (_s.value.navigating) startSim()
    }

    // ---------------------------------------------------------------- GPS

    fun hasLocationPermission() =
        ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Used once at start-up so the demo begins at the real position when there is one. */
    fun seedPosition(lat: Double, lon: Double) {
        if (!_s.value.navigating) _s.update { it.copy(pos = LatLon(lat, lon), hasFix = true) }
    }

    /** A validated, fresh fix chosen by [com.ohm.launcher.loc.LocationHub]. */
    /** Last time a real GPS fix showed movement (> 2 km/h). */
    private var lastRealMoveAt = 0L
    private val realGpsMoving get() = System.currentTimeMillis() - lastRealMoveAt < 8000

    private fun onFix(f: com.ohm.launcher.loc.Fix) {
        val s = _s.value
        // Only a running demo drive overrides real GPS; otherwise real positions always win,
        // also with the Demo car source (e.g. an emulator GPS route or a real test drive).
        if (s.simulated && simJob?.isActive == true) return
        val p = LatLon(f.lat, f.lon)
        val kmh = f.speedKmh ?: 0f
        if (kmh > 2) lastRealMoveAt = System.currentTimeMillis()
        if (s.simulated) Ohm.car.demo?.let { demo ->
            // Keep the demo car consistent with the real movement.
            if (kmh > 5 && Ohm.car.state.value.gear == Gear.P) demo.setGear(Gear.D)
            demo.setMotion(kmh, braking = kmh < s.speedKmh - 3)
        }
        val bearing = if (f.bearing != null && kmh > 6) f.bearing else s.bearing
        Ohm.car.gpsSpeed(kmh)
        _s.update { it.copy(pos = p, bearing = bearing, speedKmh = kmh, hasFix = true) }
        val r = s.route
        if (s.navigating && r != null) {
            val (along, off) = GeoMath.project(r.points, r.cum, p, s.progressM)
            // Poor fixes shouldn't trigger a reroute.
            if (off > 60 && (f.accuracyM ?: 20f) < 40f) {
                if (++offRouteCount >= 4) { offRouteCount = 0; reroute() }
            } else if (off <= 60) {
                offRouteCount = 0
                onProgress(along)
            }
        }
    }

    // ---------------------------------------------------------------- Routing

    fun preview(dest: Place) {
        _s.update { it.copy(destination = dest, previews = emptyList(), selected = 0, loadingRoute = true, error = null, arrived = false) }
        scope.launch {
            val from = _s.value.pos
            val routes = try {
                Geo.routes(from, dest.pos, Ohm.prefs.avoidTolls.value, Ohm.prefs.avoidHighways.value)
            } catch (e: Exception) {
                if (_s.value.simulated) listOf(syntheticRoute(from, dest.pos)) else {
                    _s.update { it.copy(loadingRoute = false, error = e.message ?: "Routing failed") }
                    return@launch
                }
            }
            _s.update { it.copy(previews = routes, loadingRoute = false) }
        }
        Ohm.prefs.recentPlaces.set((listOf(dest.encode()) + Ohm.prefs.recentPlaces.value.filter { Place.decode(it)?.name != dest.name }).take(8))
    }

    fun selectPreview(i: Int) = _s.update { it.copy(selected = i) }

    fun cancelPreview() = _s.update { it.copy(destination = if (it.navigating) it.destination else null, previews = emptyList(), error = null) }

    fun start() {
        val s = _s.value
        val r = s.previews.getOrNull(s.selected) ?: return
        announced.clear()
        _s.update {
            it.copy(route = r, previews = emptyList(), navigating = true, progressM = 0.0, stepIdx = 1.coerceAtMost(r.steps.size - 1),
                remainingM = r.distanceM, remainingS = r.durationS, arrived = false, camera = null)
        }
        onProgress(0.0)
        // Demo drive only when the car isn't really moving (otherwise follow real GPS).
        if (s.simulated && !realGpsMoving) {
            Ohm.car.demo?.setGear(Gear.D)
            startSim()
        }
        if (Ohm.prefs.speedCameras.value) loadCameras(r)
        loadSpeedLimits(r)
        speak(Strings.tr(Ohm.prefs.language.value, "Starting route") + ". " + Instructions.text(r.steps.getOrNull(1), Ohm.prefs.language.value))
    }

    private var cameras: List<SpeedCamera> = emptyList()

    private fun loadSpeedLimits(r: Route) {
        if (r.maxspeed.isNotEmpty() || r.nodes.isEmpty()) return
        scope.launch {
            val limits = runCatching { Geo.speedLimits(r) }.onFailure { com.ohm.launcher.core.OhmLog.w("Nav", "Speed limits", it) }.getOrNull() ?: return@launch
            if (_s.value.route === r) {
                val withLimits = r.copy(maxspeed = limits)
                _s.update { it.copy(route = withLimits, speedLimit = withLimits.speedLimitAt(it.progressM)) }
                com.ohm.launcher.core.OhmLog.i("Nav", "Speed limits for ${limits.count { it != null }}/${limits.size} segments")
            }
        }
    }

    private fun loadCameras(r: Route) {
        cameras = emptyList()
        scope.launch {
            cameras = runCatching { Geo.speedCameras(r) }.getOrDefault(emptyList())
            if (cameras.isEmpty() && _s.value.simulated && r.distanceM > 1500) {
                // Demo: put a virtual camera 60 % of the way so the alert can be seen.
                val at = r.distanceM * 0.6
                cameras = listOf(SpeedCamera(GeoMath.along(r.points, r.cum, at).first, at, r.speedLimitAt(at) ?: 50))
            }
        }
    }

    fun stop() {
        simJob?.cancel(); simJob = null
        _s.update { it.copy(route = null, navigating = false, destination = null, previews = emptyList(), camera = null, speedLimit = null) }
        if (_s.value.simulated) { Ohm.car.demo?.setMotion(0f, false); Ohm.car.demo?.setGear(Gear.P); _s.update { it.copy(speedKmh = 0f) } }
    }

    private fun reroute() {
        val dest = _s.value.destination ?: return
        scope.launch {
            val r = runCatching { Geo.routes(_s.value.pos, dest.pos, Ohm.prefs.avoidTolls.value, Ohm.prefs.avoidHighways.value).first() }.getOrNull() ?: return@launch
            announced.clear()
            _s.update { it.copy(route = r, progressM = 0.0, stepIdx = 1.coerceAtMost(r.steps.size - 1)) }
            speak(Strings.tr(Ohm.prefs.language.value, "Rerouting"))
        }
    }

    /** Straight-line fallback so the demo works offline. */
    private fun syntheticRoute(a: LatLon, b: LatLon): Route {
        val n = 40
        val pts = (0..n).map { GeoMath.lerp(a, b, it / n.toDouble()) }
        val cum = GeoMath.cumulative(pts)
        val d = cum.last()
        return Route(pts, cum, d, d / (45 / 3.6), listOf(
            Step("depart", null, "", null, a, 0.0, emptyList()),
            Step("arrive", null, "", null, b, d, emptyList()),
        ), emptyList())
    }

    // ---------------------------------------------------------------- Progress

    private fun onProgress(along: Double) {
        val s = _s.value
        val r = s.route ?: return
        var idx = s.stepIdx
        while (idx < r.steps.size - 1 && r.steps[idx].atM < along + 8) idx++
        val step = r.steps.getOrNull(idx)
        val distToStep = max(0.0, (step?.atM ?: r.distanceM) - along)
        val remaining = max(0.0, r.distanceM - along)
        val cam = cameras.firstOrNull { it.atM > along && it.atM - along < 500 }
        if (cam != null && s.camera == null) speak(Strings.tr(Ohm.prefs.language.value, "Speed camera ahead"))
        _s.update {
            it.copy(progressM = along, stepIdx = idx, distToStepM = distToStep, remainingM = remaining,
                remainingS = r.durationS * (remaining / r.distanceM.coerceAtLeast(1.0)),
                speedLimit = r.speedLimitAt(along), camera = cam, cameraDistM = cam?.let { c -> c.atM - along } ?: 0.0)
        }
        // Voice guidance: once at ~400 m and once at ~60 m before each maneuver.
        if (step != null) {
            val lang = Ohm.prefs.language.value
            if (distToStep < 420 && distToStep > 150 && announced.add("$idx-far") && step.atM - (r.steps.getOrNull(idx - 1)?.atM ?: 0.0) > 500) {
                speak(Strings.tr(lang, "In") + " " + com.ohm.launcher.core.Fmt.distance(distToStep, Ohm.prefs.distanceKm.value) + ", " + Instructions.text(step, lang).replaceFirstChar { it.lowercase(Locale.ROOT) })
            }
            if (distToStep < 60 && announced.add("$idx-near")) speak(Instructions.text(step, lang))
        }
        if (remaining < 15) arrive()
    }

    private fun arrive() {
        if (_s.value.arrived) return
        simJob?.cancel(); simJob = null
        _s.update { it.copy(arrived = true, navigating = false, route = null, camera = null, speedLimit = null) }
        if (_s.value.simulated) { Ohm.car.demo?.setMotion(0f, false); Ohm.car.demo?.setGear(Gear.P); _s.update { it.copy(speedKmh = 0f) } }
        speak(Strings.tr(Ohm.prefs.language.value, "You have arrived"))
        Ohm.banners.info(Strings.tr(Ohm.prefs.language.value, "You have arrived"), _s.value.destination?.name ?: "")
    }

    fun dismissArrived() = _s.update { it.copy(arrived = false, destination = null) }

    /** A destination shared from the phone (Ohm Companion): coordinates, or a text to search. */
    fun destinationFromPhone(name: String, lat: Double?, lon: Double?, query: String?) {
        scope.launch {
            val lang = Ohm.prefs.language.value
            val place = when {
                lat != null && lon != null -> Place(name.ifBlank { Strings.tr(lang, "Shared location") }, Strings.tr(lang, "From your phone"), LatLon(lat, lon))
                !query.isNullOrBlank() -> runCatching { Geo.search(query, _s.value.pos, lang).firstOrNull() }.getOrNull()
                else -> null
            }
            if (place == null) {
                Ohm.banners.info(Strings.tr(lang, "From your phone"), Strings.tr(lang, "Couldn't find") + " \"${query ?: name}\"")
                return@launch
            }
            com.ohm.launcher.core.OhmLog.i("Nav", "Destination from phone: ${place.name}")
            Ohm.banners.info(Strings.tr(lang, "From your phone"), place.name)
            NavProviders.navigate(ctx, place)
        }
    }

    // ---------------------------------------------------------------- Simulation

    private fun startSim() {
        simJob?.cancel()
        simJob = scope.launch {
            var v = 0.0 // m/s
            var last = System.nanoTime()
            while (isActive) {
                delay(100)
                val now = System.nanoTime()
                val dt = (now - last) / 1e9; last = now
                val s = _s.value
                val r = s.route ?: break
                val gear = Ohm.car.state.value.gear
                val limitKmh = (s.speedLimit ?: 50).coerceIn(20, 120).toDouble()
                var target = when (gear) { Gear.D -> limitKmh * 0.95; else -> 0.0 } / 3.6
                if (s.distToStepM < 90 && s.nextStep?.type != "arrive") target = min(target, 25 / 3.6)
                if (s.remainingM < 120) target = min(target, max(8.0, s.remainingM / 6) / 3.6)
                if (s.camera != null && s.cameraDistM < 250) target = min(target, ((s.camera.maxspeed ?: 50) - 3) / 3.6)
                val prev = v
                v = if (v < target) min(target, v + 2.2 * dt) else max(target, v - 3.0 * dt)
                val braking = v < prev - 0.15
                val along = s.progressM + v * dt
                val (p, brg) = GeoMath.along(r.points, r.cum, along)
                // Smooth heading so the camera doesn't jerk on polyline corners.
                val smooth = (s.bearing + GeoMath.angleDiff(s.bearing, brg) * min(1.0, dt * 4) + 360) % 360
                val kmh = (v * 3.6).toFloat()
                _s.update { it.copy(pos = p, bearing = smooth, speedKmh = kmh) }
                Ohm.car.demo?.setMotion(kmh, braking)
                onProgress(along)
            }
        }
    }

    // ---------------------------------------------------------------- Voice

    private fun speak(text: String) {
        if (!Ohm.prefs.voice.value || !ttsReady || text.isBlank()) return
        val t = tts ?: return
        t.language = if (Ohm.prefs.language.value == "pt") Locale("pt", "PT") else Locale.UK
        t.speak(text, TextToSpeech.QUEUE_ADD, null, "ohm-${System.nanoTime()}")
    }

    fun recenterTo(p: LatLon) = _s.update { it.copy(pos = p) }

    fun release() {
        tts?.shutdown()
    }
}

/** Turns OSRM maneuvers into spoken / displayed text. */
object Instructions {
    fun text(step: Step?, lang: String): String {
        step ?: return ""
        val pt = lang == "pt"
        val dir = when (step.modifier) {
            "uturn" -> if (pt) "Faça inversão de marcha" else "Make a U-turn"
            "sharp right" -> if (pt) "Vire acentuadamente à direita" else "Turn sharp right"
            "right" -> if (pt) "Vire à direita" else "Turn right"
            "slight right" -> if (pt) "Mantenha-se à direita" else "Bear right"
            "sharp left" -> if (pt) "Vire acentuadamente à esquerda" else "Turn sharp left"
            "left" -> if (pt) "Vire à esquerda" else "Turn left"
            "slight left" -> if (pt) "Mantenha-se à esquerda" else "Bear left"
            else -> if (pt) "Siga em frente" else "Continue straight"
        }
        val onto = if (step.name.isNotBlank()) (if (pt) " para ${step.name}" else " onto ${step.name}") else ""
        return when (step.type) {
            "depart" -> (if (pt) "Siga" else "Head out") + onto
            "arrive" -> if (pt) "Chegou ao destino" else "You have arrived"
            "roundabout", "rotary", "roundabout turn" ->
                if (step.exit != null) (if (pt) "Na rotunda, saia na ${step.exit}.ª saída" else "At the roundabout, take exit ${step.exit}") + onto
                else (if (pt) "Entre na rotunda" else "Enter the roundabout") + onto
            "exit roundabout", "exit rotary" -> (if (pt) "Saia da rotunda" else "Exit the roundabout") + onto
            "merge" -> (if (pt) "Entre na via" else "Merge") + onto
            "on ramp" -> (if (pt) "Entre no acesso" else "Take the ramp") + onto
            "off ramp" -> (if (pt) "Saia" else "Take the exit") + onto
            "fork" -> (if (step.modifier?.contains("left") == true) (if (pt) "Na bifurcação, mantenha-se à esquerda" else "At the fork, keep left")
                else (if (pt) "Na bifurcação, mantenha-se à direita" else "At the fork, keep right")) + onto
            "end of road" -> dir + onto
            "continue", "new name" -> if (step.modifier == null || step.modifier == "straight") (if (pt) "Continue" else "Continue") + onto else dir + onto
            else -> dir + onto
        }
    }

    fun shortName(step: Step?): String = step?.name?.takeIf { it.isNotBlank() } ?: ""

    fun isLeft(step: Step?) = step?.modifier?.contains("left") == true
    fun absAngle(step: Step?) = abs(0)
}
