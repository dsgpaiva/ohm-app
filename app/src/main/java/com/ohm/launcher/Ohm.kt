package com.ohm.launcher

import android.app.Application
import com.ohm.launcher.apps.AppsRepo
import com.ohm.launcher.car.CarHub
import com.ohm.launcher.core.Banners
import com.ohm.launcher.core.CapabilityDetector
import com.ohm.launcher.core.DayNight
import com.ohm.launcher.core.OhmLog
import com.ohm.launcher.core.Prefs
import com.ohm.launcher.core.Profiles
import com.ohm.launcher.core.ReverseController
import com.ohm.launcher.core.SystemRepo
import com.ohm.launcher.core.Trips
import com.ohm.launcher.core.WeatherRepo
import com.ohm.launcher.loc.LocationHub
import com.ohm.launcher.media.MediaRepo
import com.ohm.launcher.nav.NavController
import com.ohm.launcher.ui.theme.Accent
import com.ohm.launcher.ui.theme.Motion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre

/**
 * App-wide singletons (simple service locator).
 * Guiding principle: one interface, interchangeable data sources (car, location, media, navigation).
 */
object Ohm {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var app: Application
    lateinit var prefs: Prefs
    lateinit var profiles: Profiles
    lateinit var caps: CapabilityDetector
    lateinit var car: CarHub
    lateinit var location: LocationHub
    lateinit var compass: com.ohm.launcher.loc.Compass
    lateinit var nav: NavController
    lateinit var media: MediaRepo
    lateinit var apps: AppsRepo
    lateinit var weather: WeatherRepo
    lateinit var dayNight: DayNight
    lateinit var system: SystemRepo
    lateinit var banners: Banners
    lateinit var reverse: ReverseController
    lateinit var trips: Trips
    lateinit var obd: com.ohm.launcher.obd.ObdManager
    lateinit var updater: com.ohm.launcher.core.Updater
    lateinit var driving: com.ohm.launcher.core.Driving
    lateinit var speedVolume: com.ohm.launcher.core.SpeedVolume
    lateinit var spotify: com.ohm.launcher.spotify.Spotify
    lateinit var receiver: com.ohm.launcher.spotify.Receiver

    @OptIn(FlowPreview::class)
    fun init(a: Application) {
        app = a
        prefs = Prefs(a)
        profiles = Profiles(prefs).also { it.init() }
        banners = Banners(scope)
        car = CarHub(a)
        location = LocationHub(a, scope)
        compass = com.ohm.launcher.loc.Compass(a)
        nav = NavController(a, scope)
        media = MediaRepo(a, scope)
        apps = AppsRepo(a, scope)
        weather = WeatherRepo(scope)
        system = SystemRepo(a)
        dayNight = DayNight(a, scope)
        reverse = ReverseController(a, scope)
        trips = Trips(scope)
        obd = com.ohm.launcher.obd.ObdManager(a, scope)
        updater = com.ohm.launcher.core.Updater(a, scope)
        driving = com.ohm.launcher.core.Driving(a, scope)
        speedVolume = com.ohm.launcher.core.SpeedVolume(a, scope)
        caps = CapabilityDetector(a, scope)
        spotify = com.ohm.launcher.spotify.Spotify(a, scope)
        receiver = com.ohm.launcher.spotify.Receiver(a, scope)

        scope.launch {
            combine(prefs.carSource.state, prefs.vendorActions.state, prefs.vendorMap.state) { s, v, m -> Triple(s, v, m) }
                .collect { (s, v, m) -> car.use(s, v, m); OhmLog.i("Car", "Car source: $s") }
        }
        // Any change to a vehicle setting is saved into the active vehicle profile.
        scope.launch {
            merge(*prefs.vehicleScoped.map { it.state }.toTypedArray()).drop(1).debounce(500).collect { profiles.save() }
        }
        scope.launch { prefs.accent.state.collect { Accent.select(it) } }
        scope.launch { prefs.motion.state.collect { Motion.set(it) } }

        nav.init()
        location.init()
        media.init()
        media.keepTicking()
        spotify.init()
        receiver.init()
        apps.init()
        system.init()
        weather.init()
        dayNight.init()
        reverse.init()
        trips.init()
        obd.init()
        updater.init()
        driving.init()
        speedVolume.init()
        caps.init()
        banners.watchConnectivity(system)
        banners.watchReverse()
        OhmLog.i("App", "Ohm started (vehicle: ${prefs.vehicleName.value})")
    }
}

class OhmApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        Ohm.init(this)
    }
}
