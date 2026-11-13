package com.ohm.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EvStation
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.ForkLeft
import androidx.compose.material.icons.rounded.ForkRight
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.LocalParking
import androidx.compose.material.icons.rounded.Merge
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.RampLeft
import androidx.compose.material.icons.rounded.RampRight
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.RoundaboutRight
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Straight
import androidx.compose.material.icons.rounded.TurnLeft
import androidx.compose.material.icons.rounded.TurnRight
import androidx.compose.material.icons.rounded.TurnSharpLeft
import androidx.compose.material.icons.rounded.TurnSharpRight
import androidx.compose.material.icons.rounded.TurnSlightLeft
import androidx.compose.material.icons.rounded.TurnSlightRight
import androidx.compose.material.icons.rounded.UTurnLeft
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ohm.launcher.Ohm
import com.ohm.launcher.core.Fmt
import com.ohm.launcher.core.GeoMath
import com.ohm.launcher.core.LatLon
import com.ohm.launcher.nav.Geo
import com.ohm.launcher.nav.Instructions
import com.ohm.launcher.nav.NavState
import com.ohm.launcher.nav.Place
import com.ohm.launcher.nav.Step
import com.ohm.launcher.ui.theme.Accent
import com.ohm.launcher.ui.theme.LocalLowPower
import com.ohm.launcher.ui.theme.OhmTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shared handle so the floating controls can zoom the map. */
object MapRef {
    var map: MapLibreMap? = null
    /** Zoom used while following (browsing / navigating); the +/- buttons change these. */
    var followZoom = 15.5
    var navZoom = 16.6
    var navigating = false
    var following = true

    fun zoomBy(delta: Double) {
        if (following) {
            if (navigating) navZoom = (navZoom + delta).coerceIn(12.0, 19.0) else followZoom = (followZoom + delta).coerceIn(3.0, 19.5)
        } else map?.animateCamera(if (delta > 0) CameraUpdateFactory.zoomIn() else CameraUpdateFactory.zoomOut(), 250)
    }
}

/** Point [meters] ahead of [p] along [bearingDeg]. */
private fun ahead(p: LatLon, bearingDeg: Double, meters: Double): LatLon {
    val b = Math.toRadians(bearingDeg)
    return LatLon(p.lat + meters * kotlin.math.cos(b) / 111_320.0,
        p.lon + meters * kotlin.math.sin(b) / (111_320.0 * kotlin.math.cos(Math.toRadians(p.lat))))
}

/** Navigation arrow (accent fill, white outline) drawn once per style. */
private fun arrowBitmap(color: Int): android.graphics.Bitmap {
    val s = 112
    val bmp = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888)
    val cv = android.graphics.Canvas(bmp)
    val path = android.graphics.Path().apply {
        moveTo(s / 2f, 12f); lineTo(s - 22f, s - 18f); lineTo(s / 2f, s - 38f); lineTo(22f, s - 18f); close()
    }
    val stroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = android.graphics.Color.WHITE; style = android.graphics.Paint.Style.STROKE
        strokeWidth = 9f; strokeJoin = android.graphics.Paint.Join.ROUND
        setShadowLayer(8f, 0f, 2f, 0x66000000)
    }
    val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = android.graphics.Paint.Style.FILL }
    cv.drawPath(path, stroke); cv.drawPath(path, fill)
    return bmp
}

private const val STYLE_LIGHT = "https://basemaps.cartocdn.com/gl/positron-gl-style/style.json"
private const val STYLE_DARK = "https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json"
private const val STYLE_SAT = """{"version":8,"sources":{"sat":{"type":"raster","tiles":["https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"],"tileSize":256,"maxzoom":19,"attribution":"Imagery © Esri, Maxar, Earthstar Geographics"}},"layers":[{"id":"sat","type":"raster","source":"sat"}]}"""

private fun LatLon.ll() = LatLng(lat, lon)
private fun LatLon.pt() = Point.fromLngLat(lon, lat)

@Composable
fun MapPanel(modifier: Modifier) {
    val ctx = LocalContext.current
    val ui = LocalUi.current
    val c = OhmTheme.colors
    val lowPower = LocalLowPower.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapView(ctx).apply { onCreate(null) } }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleGen by remember { mutableIntStateOf(0) }
    val styleMode by Ohm.prefs.mapStyle.state.collectAsState()
    val buildings by Ohm.prefs.buildings3d.state.collectAsState()
    val headingUp by Ohm.prefs.headingUp.state.collectAsState()
    val nav by Ohm.nav.state.collectAsState()
    val density = LocalDensity.current

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(Unit) {
        mapView.getMapAsync { m ->
            m.uiSettings.isCompassEnabled = false
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isAttributionEnabled = true
            m.uiSettings.setAttributionMargins(0, 0, 8, 8)
            m.uiSettings.attributionGravity = android.view.Gravity.BOTTOM or android.view.Gravity.START
            m.setMaxPitchPreference(60.0)
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) ui.follow = false
            }
            m.cameraPosition = CameraPosition.Builder().target(Ohm.nav.state.value.pos.ll()).zoom(15.0).build()
            MapRef.map = m
            map = m
        }
    }
    LaunchedEffect(lowPower) { mapView.setMaximumFps(if (lowPower) 30 else 60) }

    val dark = c.isDark
    val styleKey = when (styleMode) { "satellite" -> "sat"; "light" -> "light"; "dark" -> "dark"; else -> if (dark) "dark" else "light" }
    LaunchedEffect(map, styleKey, buildings) {
        val m = map ?: return@LaunchedEffect
        val b = when (styleKey) { "sat" -> Style.Builder().fromJson(STYLE_SAT); "dark" -> Style.Builder().fromUri(STYLE_DARK); else -> Style.Builder().fromUri(STYLE_LIGHT) }
        m.setStyle(b) { style -> addOhmLayers(style, styleKey, buildings); styleGen++ }
    }

    // ---- route / alternatives / destination
    LaunchedEffect(styleGen, nav.route, nav.previews, nav.selected, nav.destination) {
        val style = map?.style ?: return@LaunchedEffect
        if (!style.isFullyLoaded) return@LaunchedEffect
        val main = nav.route ?: nav.previews.getOrNull(nav.selected)
        style.getSourceAs<GeoJsonSource>("ohm-route")?.setGeoJson(
            if (main != null) FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(main.points.map { it.pt() }))) else FeatureCollection.fromFeatures(emptyList())
        )
        style.getSourceAs<GeoJsonSource>("ohm-alts")?.setGeoJson(FeatureCollection.fromFeatures(
            if (nav.route == null) nav.previews.filterIndexed { i, _ -> i != nav.selected }.map { r -> Feature.fromGeometry(LineString.fromLngLats(r.points.map { it.pt() })) } else emptyList()
        ))
        style.getSourceAs<GeoJsonSource>("ohm-dest")?.setGeoJson(
            nav.destination?.let { FeatureCollection.fromFeature(Feature.fromGeometry(it.pos.pt())) } ?: FeatureCollection.fromFeatures(emptyList())
        )
        // Frame the whole route when previewing
        if (nav.route == null && main != null) {
            val m = map ?: return@LaunchedEffect
            ui.follow = false
            val bounds = LatLngBounds.Builder().includes(main.points.map { it.ll() }).build()
            val pad = with(density) { 80.dp.toPx().toInt() }
            m.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, pad, pad * 2, pad, pad * 3), 600)
        }
    }

    // ---- puck + follow camera, animated every frame.
    // GPS gives ~1 fix/s; between fixes the position is predicted from speed and heading and
    // eased, so the arrow and camera glide instead of jumping once a second.
    val navNow by androidx.compose.runtime.rememberUpdatedState(nav)
    val followNow by androidx.compose.runtime.rememberUpdatedState(ui.follow)
    val headingUpNow by androidx.compose.runtime.rememberUpdatedState(headingUp)
    val compass by Ohm.compass.heading.collectAsState()
    val compassNow by androidx.compose.runtime.rememberUpdatedState(compass)
    val lowPowerNow by androidx.compose.runtime.rememberUpdatedState(lowPower)
    // Compass only while (nearly) standing still: the GPS course is better when moving.
    LaunchedEffect(nav.speedKmh < 5) { Ohm.compass.setActive(nav.speedKmh < 5) }
    DisposableEffect(Unit) { onDispose { Ohm.compass.setActive(false) } }

    LaunchedEffect(map, styleGen) {
        val m = map ?: return@LaunchedEffect
        val style = m.style ?: return@LaunchedEffect
        if (!style.isFullyLoaded) return@LaunchedEffect
        val puck = style.getSourceAs<GeoJsonSource>("ohm-puck") ?: return@LaunchedEffect
        var disp = navNow.pos
        var dispBrg = navNow.bearing
        var fixPos = navNow.pos
        var fixAtNs = 0L
        var lastNs = 0L
        var zoom = m.cameraPosition.zoom
        var tilt = m.cameraPosition.tilt
        var wasFollowing = false
        var settleUntilNs = 0L
        var camBrgS = m.cameraPosition.bearing
        while (true) {
            val ns = androidx.compose.runtime.withFrameNanos { it }
            if (lastNs == 0L) { lastNs = ns; fixAtNs = ns; continue }
            if (lowPowerNow && ns - lastNs < 33_000_000) continue
            val dt = ((ns - lastNs) / 1e9).coerceIn(0.0, 0.25)
            lastNs = ns
            val n = navNow
            if (n.pos != fixPos) { fixPos = n.pos; fixAtNs = ns }
            val moving = n.speedKmh > 3 || (n.navigating && n.simulated)
            // Gentle prediction: at most 0.8 s ahead, at 90 % of the speed, so it rarely
            // overshoots a bend and then has to snap sideways when the next fix arrives.
            val since = ((ns - fixAtNs) / 1e9).coerceAtMost(0.8)
            var target = if (moving) ahead(fixPos, n.bearing, since * n.speedKmh / 3.6 * 0.9) else fixPos
            var targetBrg = if (moving) n.bearing else compassNow?.toDouble() ?: n.bearing
            // Navigating on real GPS: snap onto the route line (map matching), like in-car nav does.
            val route = n.route
            if (n.navigating && route != null && !n.simulated) {
                val (along, off) = GeoMath.project(route.points, route.cum, target, n.progressM)
                if (off < 25) {
                    val (p, b) = GeoMath.along(route.points, route.cum, along)
                    target = p
                    if (moving) targetBrg = b
                }
            }
            if (GeoMath.dist(disp, target) > 300) disp = target // big jump (new fix source, teleport)
            // Idle (parked, everything settled): don't redraw the map every frame.
            val wantZoomNow = if (n.navigating) MapRef.navZoom else MapRef.followZoom
            val settled = GeoMath.dist(disp, target) < 0.05 && kotlin.math.abs(GeoMath.angleDiff(dispBrg, targetBrg)) < 0.2 &&
                (!followNow || (kotlin.math.abs(zoom - wantZoomNow) < 0.005 && wasFollowing && ns >= settleUntilNs))
            if (settled && followNow == wasFollowing) continue
            val a = 1 - kotlin.math.exp(-dt / 0.15)
            disp = LatLon(disp.lat + (target.lat - disp.lat) * a, disp.lon + (target.lon - disp.lon) * a)
            dispBrg = (dispBrg + GeoMath.angleDiff(dispBrg, targetBrg) * (1 - kotlin.math.exp(-dt / 0.35)) + 360) % 360
            puck.setGeoJson(Feature.fromGeometry(disp.pt()).apply { addNumberProperty("bearing", dispBrg) })

            MapRef.navigating = n.navigating
            MapRef.following = followNow
            if (followNow) {
                if (!wasFollowing) { // recentre smoothly, then hand over to the per-frame camera
                    zoom = m.cameraPosition.zoom; tilt = m.cameraPosition.tilt
                    settleUntilNs = ns + 500_000_000
                    m.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(m.cameraPosition).target(disp.ll()).build()), 500)
                }
                if (ns >= settleUntilNs) {
                    val wantZoom = if (n.navigating) MapRef.navZoom else MapRef.followZoom
                    zoom += (wantZoom - zoom) * (1 - kotlin.math.exp(-dt / 0.25))
                    tilt += ((if (n.navigating) 52.0 else 0.0) - tilt) * (1 - kotlin.math.exp(-dt / 0.3))
                    // The map turns slower than the arrow and ignores wobbles under 3°,
                    // so small heading noise moves the arrow, not the whole map.
                    val camTarget = when {
                        headingUpNow && (moving || compassNow != null) -> dispBrg
                        headingUpNow -> camBrgS
                        else -> 0.0
                    }
                    val camDiff = GeoMath.angleDiff(camBrgS, camTarget)
                    if (kotlin.math.abs(camDiff) > 3 || !headingUpNow) camBrgS = (camBrgS + camDiff * (1 - kotlin.math.exp(-dt / 0.9)) + 360) % 360
                    val camBrg = camBrgS
                    val h = mapView.height.toDouble()
                    m.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder()
                        .target(disp.ll()).zoom(zoom).bearing(camBrg).tilt(tilt)
                        .padding(0.0, if (n.navigating) h * 0.38 else 0.0, 0.0, 0.0).build()))
                }
            } else if (wasFollowing) MapRef.followZoom = m.cameraPosition.zoom.coerceIn(3.0, 19.5)
            wasFollowing = followNow
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private fun addOhmLayers(style: Style, key: String, buildings: Boolean) {
    val accent = Accent.accent.toArgb()
    style.addSource(GeoJsonSource("ohm-alts"))
    style.addSource(GeoJsonSource("ohm-route"))
    style.addSource(GeoJsonSource("ohm-dest"))
    style.addSource(GeoJsonSource("ohm-puck"))
    if (buildings && key != "sat") runCatching {
        // CARTO styles use the OpenMapTiles schema with source id "carto".
        style.addLayer(FillExtrusionLayer("ohm-3d", "carto").withSourceLayer("building").withProperties(
            PropertyFactory.fillExtrusionHeight(Expression.coalesce(Expression.get("render_height"), Expression.literal(8))),
            PropertyFactory.fillExtrusionBase(Expression.coalesce(Expression.get("render_min_height"), Expression.literal(0))),
            PropertyFactory.fillExtrusionColor(if (key == "dark") "#2A2C30" else "#E3E4E6"),
            PropertyFactory.fillExtrusionOpacity(0.75f),
        ).also { it.minZoom = 15f })
    }
    style.addLayer(LineLayer("ohm-alts-line", "ohm-alts").withProperties(
        PropertyFactory.lineColor(if (key == "dark") "#5A5D63" else "#A2A3A5"), PropertyFactory.lineWidth(6f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
    ))
    style.addLayer(LineLayer("ohm-route-casing", "ohm-route").withProperties(
        PropertyFactory.lineColor("#0045C4"), PropertyFactory.lineWidth(11f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
    ))
    style.addLayer(LineLayer("ohm-route-line", "ohm-route").withProperties(
        PropertyFactory.lineColor(accent), PropertyFactory.lineWidth(7f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
    ))
    style.addLayer(CircleLayer("ohm-dest-dot", "ohm-dest").withProperties(
        PropertyFactory.circleRadius(9f), PropertyFactory.circleColor(Accent.danger.toArgb()),
        PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(3f),
    ))
    style.addLayer(CircleLayer("ohm-puck-halo", "ohm-puck").withProperties(
        PropertyFactory.circleRadius(24f), PropertyFactory.circleColor(accent), PropertyFactory.circleOpacity(0.16f),
        PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP),
    ))
    // Direction arrow: rotates with the heading, lies flat on the (tilted) map.
    style.addImage("ohm-arrow", arrowBitmap(accent))
    style.addLayer(SymbolLayer("ohm-puck-arrow", "ohm-puck").withProperties(
        PropertyFactory.iconImage("ohm-arrow"),
        PropertyFactory.iconRotate(Expression.toNumber(Expression.get("bearing"))),
        PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
        PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
        PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
        PropertyFactory.iconSize(0.5f),
    ))
}

// ======================================================================= map controls

@Composable
fun MapControls(modifier: Modifier) {
    val ui = LocalUi.current
    val c = OhmTheme.colors
    val headingUp by Ohm.prefs.headingUp.state.collectAsState()
    val style by Ohm.prefs.mapStyle.state.collectAsState()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.End) {
        MapFab(Icons.Rounded.Layers, tr("Map style")) {
            Ohm.prefs.mapStyle.set(when (style) { "auto" -> "satellite"; "satellite" -> "light"; "light" -> "dark"; else -> "auto" })
            Ohm.banners.info(trNow("Map style"), trNow(when (style) { "auto" -> "Satellite"; "satellite" -> "Light"; "light" -> "Dark"; else -> "Automatic" }))
        }
        MapFab(if (headingUp) Icons.Rounded.Navigation else Icons.Rounded.Explore, if (headingUp) tr("Heading up") else tr("North up")) {
            Ohm.prefs.headingUp.set(!headingUp)
            if (headingUp) MapRef.map?.animateCamera(CameraUpdateFactory.bearingTo(0.0), 300)
        }
        Column(Modifier.shadow(if (c.isDark) 0.dp else 6.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(c.card)) {
            IconBtn(Icons.Rounded.Add, tr("Zoom in"), size = 52.dp) { MapRef.zoomBy(1.0) }
            Box(Modifier.width(52.dp).height(1.dp).background(c.divider))
            IconBtn(Icons.Rounded.Remove, tr("Zoom out"), size = 52.dp) { MapRef.zoomBy(-1.0) }
        }
        MapFab(Icons.Rounded.MyLocation, tr("Recenter"), tint = if (ui.follow) Accent.accent else c.icon) { ui.follow = true }
    }
}

@Composable
private fun MapFab(icon: ImageVector, cd: String, tint: Color = OhmTheme.colors.icon, onClick: () -> Unit) {
    val c = OhmTheme.colors
    IconBtn(icon, cd, size = 52.dp, tint = tint, background = c.card,
        modifier = Modifier.shadow(if (c.isDark) 0.dp else 6.dp, CircleShape), onClick = onClick)
}

// ======================================================================= search

@Composable
fun SearchEntry(modifier: Modifier) {
    val ui = LocalUi.current
    val c = OhmTheme.colors
    val nav by Ohm.nav.state.collectAsState()
    if (nav.previews.isNotEmpty() || nav.loadingRoute || nav.error != null || nav.arrived) return
    Row(
        modifier.widthIn(min = 260.dp, max = 360.dp).height(52.dp)
            .shadow(if (c.isDark) 0.dp else 8.dp, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp)).background(c.card)
            .clickable { ui.sheet = Sheet.SEARCH }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = c.iconMuted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(tr("Navigate…"), style = OhmTheme.type.body, color = c.textHint)
    }
}

private val DemoPlaces = listOf(
    Place("Torre de Belém", "Av. Brasília, Lisboa", LatLon(38.69160, -9.21590)),
    Place("Parque das Nações", "Lisboa", LatLon(38.76810, -9.09440)),
    Place("Estádio José Alvalade", "Lisboa", LatLon(38.76130, -9.16090)),
)

@Composable
fun SearchSheet() {
    val ui = LocalUi.current
    val c = OhmTheme.colors
    val lang = LocalLang.current
    val scope = rememberCoroutineScope()
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val home by Ohm.prefs.home.state.collectAsState()
    val work by Ohm.prefs.work.state.collectAsState()
    val recents by Ohm.prefs.recentPlaces.state.collectAsState()
    val ctx = LocalContext.current
    val src by Ohm.car.sourceId.collectAsState()
    val focus = remember { FocusRequester() }
    val q = ui.searchQuery

    LaunchedEffect(Unit) { delay(250); runCatching { focus.requestFocus() } }
    LaunchedEffect(q) {
        if (q.length < 2) { results = emptyList(); error = null; return@LaunchedEffect }
        delay(350)
        loading = true
        try { results = Geo.search(q, Ohm.nav.state.value.pos, lang); error = null }
        catch (e: Exception) { error = trNow("Search unavailable (offline?)") }
        loading = false
    }

    fun go(p: Place) {
        ui.sheet = Sheet.NONE
        ui.searchQuery = ""
        com.ohm.launcher.nav.NavProviders.navigate(ctx, p)
    }

    fun category(cat: String) {
        loading = true; error = null
        scope.launch {
            try { results = Geo.nearby(cat, Ohm.nav.state.value.pos) ; if (results.isEmpty()) error = trNow("Nothing found nearby") }
            catch (e: Exception) { error = trNow("Search unavailable (offline?)") }
            loading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        SheetHeader(tr("Navigate"), onClose = { ui.sheet = Sheet.NONE })
        OhmTextField(
            q, { ui.searchQuery = it }, tr("Search address or place"),
            Modifier.fillMaxWidth().padding(horizontal = 20.dp), leading = Icons.Rounded.Search,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { results.firstOrNull()?.let { go(it) } }),
            focusRequester = focus,
        )
        LazyRow(Modifier.padding(vertical = 12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Pill(tr("Fuel"), false, icon = Icons.Rounded.LocalGasStation) { category("fuel") } }
            item { Pill(tr("Parking"), false, icon = Icons.Rounded.LocalParking) { category("parking") } }
            item { Pill(tr("Charging"), false, icon = Icons.Rounded.EvStation) { category("ev") } }
            item { Pill(tr("Food"), false, icon = Icons.Rounded.Restaurant) { category("food") } }
        }
        if (loading) Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp), color = Accent.accent, strokeWidth = 3.dp) }
        error?.let { Text(it, style = OhmTheme.type.body, color = c.textSecondary, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
            if (results.isEmpty() && q.length < 2) {
                Place.decode(home)?.let { p -> item { PlaceRow(p, Icons.Rounded.Home, tr("Home")) { go(p) } } }
                Place.decode(work)?.let { p -> item { PlaceRow(p, Icons.Rounded.Work, tr("Work")) { go(p) } } }
                if (src == "demo") {
                    item { Caption(tr("Demo drives"), Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp)) }
                    items(DemoPlaces) { p -> PlaceRow(p, Icons.Rounded.Flag, null) { go(p) } }
                }
                val rec = recents.mapNotNull { Place.decode(it) }
                if (rec.isNotEmpty()) {
                    item { Caption(tr("Recent"), Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp)) }
                    items(rec) { p -> PlaceRow(p, Icons.Rounded.History, null) { go(p) } }
                }
            }
            items(results) { p -> PlaceRow(p, Icons.Rounded.Place, null, showFavButtons = true) { go(p) } }
        }
    }
}

@Composable
private fun PlaceRow(p: Place, icon: ImageVector, label: String?, showFavButtons: Boolean = false, onClick: () -> Unit) {
    val c = OhmTheme.colors
    val pos = Ohm.nav.state.collectAsState().value.pos
    val metric by Ohm.prefs.distanceKm.state.collectAsState()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(c.surface), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = c.icon, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label ?: p.name, style = OhmTheme.type.bodyStrong, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = if (label != null) p.name else p.address
            if (sub.isNotEmpty()) Text(sub, style = OhmTheme.type.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(Fmt.distance(GeoMath.dist(pos, p.pos), metric), style = OhmTheme.type.caption, color = c.textSecondary)
        if (showFavButtons) {
            IconBtn(Icons.Rounded.Home, tr("Set as Home"), size = 40.dp, iconSize = 18.dp, tint = c.iconMuted) { Ohm.prefs.home.set(p.encode()); Ohm.banners.info(trNow("Home saved"), p.name) }
            IconBtn(Icons.Rounded.Work, tr("Set as Work"), size = 40.dp, iconSize = 18.dp, tint = c.iconMuted) { Ohm.prefs.work.set(p.encode()); Ohm.banners.info(trNow("Work saved"), p.name) }
        }
    }
}

// ======================================================================= navigation overlays

fun maneuverIcon(step: Step?): ImageVector {
    step ?: return Icons.Rounded.Straight
    return when (step.type) {
        "arrive" -> Icons.Rounded.Flag
        "roundabout", "rotary", "roundabout turn", "exit roundabout", "exit rotary" -> Icons.Rounded.RoundaboutRight
        "fork" -> if (Instructions.isLeft(step)) Icons.Rounded.ForkLeft else Icons.Rounded.ForkRight
        "merge" -> Icons.Rounded.Merge
        "on ramp", "off ramp" -> if (Instructions.isLeft(step)) Icons.Rounded.RampLeft else Icons.Rounded.RampRight
        else -> when (step.modifier) {
            "uturn" -> Icons.Rounded.UTurnLeft
            "sharp right" -> Icons.Rounded.TurnSharpRight
            "right" -> Icons.Rounded.TurnRight
            "slight right" -> Icons.Rounded.TurnSlightRight
            "sharp left" -> Icons.Rounded.TurnSharpLeft
            "left" -> Icons.Rounded.TurnLeft
            "slight left" -> Icons.Rounded.TurnSlightLeft
            else -> Icons.Rounded.Straight
        }
    }
}

private fun laneIcon(ind: List<String>): ImageVector = when (ind.firstOrNull()) {
    "left", "sharp left" -> Icons.Rounded.TurnLeft
    "slight left" -> Icons.Rounded.TurnSlightLeft
    "right", "sharp right" -> Icons.Rounded.TurnRight
    "slight right" -> Icons.Rounded.TurnSlightRight
    "uturn" -> Icons.Rounded.UTurnLeft
    else -> Icons.Rounded.Straight
}

@Composable
fun NavOverlays(nav: NavState) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cardW = (maxWidth * 0.42f).coerceIn(280.dp, 420.dp)
        if (nav.navigating) {
            ManeuverCard(nav, Modifier.align(Alignment.TopStart).padding(start = 20.dp, top = 52.dp).width(cardW))
            EtaPanel(nav, Modifier.align(Alignment.BottomStart).padding(16.dp).width(cardW))
        } else if (nav.loadingRoute || nav.previews.isNotEmpty() || nav.error != null) {
            RoutePreview(nav, Modifier.align(Alignment.BottomStart).padding(16.dp).width(cardW))
        } else if (nav.arrived) {
            ArrivedCard(nav, Modifier.align(Alignment.BottomStart).padding(16.dp).width(cardW))
        }
    }
}

@Composable
private fun ManeuverCard(nav: NavState, modifier: Modifier) {
    val metric by Ohm.prefs.distanceKm.state.collectAsState()
    val lang = LocalLang.current
    val step = nav.nextStep
    Column(modifier) {
        Column(Modifier.fillMaxWidth().shadow(8.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(Color(0xFF1B1C1E)).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(maneuverIcon(step), null, tint = Color.White, modifier = Modifier.size(52.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(Fmt.distance(nav.distToStepM, metric), style = OhmTheme.type.display.copy(fontSize = 30.sp), color = Color.White)
                    Text(Instructions.text(step, lang), style = OhmTheme.type.body, color = Color.White.copy(alpha = 0.8f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            val lanes = step?.lanes.orEmpty()
            if (lanes.size > 1 && nav.distToStepM < 600) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.08f)).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (l in lanes) Icon(laneIcon(l.indications), null, tint = if (l.valid) Color.White else Color.White.copy(alpha = 0.25f), modifier = Modifier.size(26.dp))
                }
            }
        }
        nav.followingStep?.takeIf { step?.type != "arrive" && (it.atM - (step?.atM ?: 0.0)) < 250 }?.let { nxt ->
            Row(Modifier.padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF2A2B2E)).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Then"), style = OhmTheme.type.label, color = Color.White.copy(alpha = 0.7f))
                Spacer(Modifier.width(8.dp))
                Icon(maneuverIcon(nxt), null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
        if (nav.camera != null) {
            Row(Modifier.padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).background(Accent.warning).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Videocam, null, tint = Color(0xFF171717), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(tr("Speed camera") + " · " + Fmt.distance(nav.cameraDistM, metric) + (nav.camera.maxspeed?.let { " · $it" } ?: ""), style = OhmTheme.type.label, color = Color(0xFF171717))
            }
        }
    }
}

@Composable
private fun EtaPanel(nav: NavState, modifier: Modifier) {
    val c = OhmTheme.colors
    val metric by Ohm.prefs.distanceKm.state.collectAsState()
    val ctx = LocalContext.current
    val eta = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(System.currentTimeMillis() + (nav.remainingS * 1000).toLong()))
    OhmCard(modifier) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(eta, style = OhmTheme.type.display.copy(fontSize = 26.sp), color = c.textPrimary)
                Text("${Fmt.duration(nav.remainingS)} · ${Fmt.distance(nav.remainingM, metric)}", style = OhmTheme.type.label, color = c.textSecondary)
                nav.destination?.let { Text(it.name, style = OhmTheme.type.caption, color = c.textHint, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            nav.destination?.let { d ->
                IconBtn(Icons.Rounded.OpenInNew, tr("Open in Google Maps"), size = 48.dp, background = c.surface) {
                    com.ohm.launcher.nav.NavProviders.handOff(ctx, d)
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.height(48.dp).clip(RoundedCornerShape(12.dp)).background(Accent.danger).clickable { Ohm.nav.stop() }.padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                Text(tr("End"), style = OhmTheme.type.bodyStrong, color = Color.White)
            }
        }
    }
}

@Composable
private fun RoutePreview(nav: NavState, modifier: Modifier) {
    val c = OhmTheme.colors
    val metric by Ohm.prefs.distanceKm.state.collectAsState()
    OhmCard(modifier) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(nav.destination?.name ?: "", style = OhmTheme.type.title, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    nav.destination?.address?.takeIf { it.isNotEmpty() }?.let { Caption(it) }
                }
                IconBtn(Icons.Rounded.Close, tr("Cancel")) { Ohm.nav.cancelPreview() }
            }
            Spacer(Modifier.height(10.dp))
            when {
                nav.loadingRoute -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = Accent.accent, strokeWidth = 3.dp)
                    Spacer(Modifier.width(12.dp)); Text(tr("Finding routes…"), style = OhmTheme.type.body, color = c.textSecondary)
                }
                nav.error != null -> Text(tr("Couldn't find a route") + ": ${nav.error}", style = OhmTheme.type.body, color = Accent.danger)
                else -> {
                    nav.previews.forEachIndexed { i, r ->
                        val sel = i == nav.selected
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (sel) Accent.accent.copy(alpha = 0.14f) else c.surface)
                                .clickable { Ohm.nav.selectPreview(i) }.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(Fmt.duration(r.durationS), style = OhmTheme.type.bodyStrong, color = if (sel) Accent.accent else c.textPrimary, modifier = Modifier.weight(1f))
                            Text(Fmt.distance(r.distanceM, metric), style = OhmTheme.type.label, color = c.textSecondary)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    val ui = LocalUi.current
                    PrimaryButton(tr("Start navigation"), Modifier.fillMaxWidth(), icon = Icons.Rounded.Navigation) { ui.follow = true; Ohm.nav.start() }
                }
            }
        }
    }
}

@Composable
private fun ArrivedCard(nav: NavState, modifier: Modifier) {
    val c = OhmTheme.colors
    OhmCard(modifier) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Flag, null, tint = Accent.success, modifier = Modifier.size(32.dp).rotate(0f))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(tr("You have arrived"), style = OhmTheme.type.title, color = c.textPrimary)
                nav.destination?.let { Caption(it.name) }
            }
            IconBtn(Icons.Rounded.Close, tr("Dismiss")) { Ohm.nav.dismissArrived() }
        }
    }
}
