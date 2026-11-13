package com.ohm.launcher.ui

import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ohm.launcher.MainActivity
import com.ohm.launcher.Ohm
import com.ohm.launcher.car.CarPaints
import com.ohm.launcher.car.Gear
import com.ohm.launcher.core.Fmt
import com.ohm.launcher.core.OhmLog
import com.ohm.launcher.core.Perf
import com.ohm.launcher.core.Pref
import com.ohm.launcher.core.Trip
import com.ohm.launcher.loc.Quality
import com.ohm.launcher.nav.NavProviders
import com.ohm.launcher.ui.theme.Accent
import com.ohm.launcher.ui.theme.OhmTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Tabs = listOf(
    "Vehicle" to Icons.Rounded.DirectionsCar,
    "Units" to Icons.Rounded.Straighten,
    "Display" to Icons.Rounded.Palette,
    "Navigation" to Icons.Rounded.Map,
    "Location" to Icons.Rounded.LocationOn,
    "Media" to Icons.Rounded.MusicNote,
    "Apps" to Icons.Rounded.Apps,
    "Trips" to Icons.Rounded.Route,
    "Diagnostics" to Icons.Rounded.BugReport,
    "System" to Icons.Rounded.Info,
)

/** "Car" sheet: tabs on the left, content on the right (Tesla Controls layout). */
@Composable
fun SettingsSheet() {
    val c = OhmTheme.colors
    val ui = LocalUi.current
    Column(Modifier.fillMaxSize()) {
        SheetHeader(tr("Controls"), onClose = { ui.sheet = Sheet.NONE })
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(200.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 4.dp, bottom = 12.dp)) {
                Tabs.forEachIndexed { i, (name, icon) ->
                    val sel = ui.settingsTab == i
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (sel) c.surface else Color.Transparent)
                            .clickable { ui.settingsTab = i }.padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(icon, null, tint = if (sel) Accent.accent else c.icon, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(tr(name), style = OhmTheme.type.bodyStrong, color = c.textPrimary)
                    }
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(c.divider))
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp)) {
                when (ui.settingsTab) {
                    0 -> VehicleTab()
                    1 -> UnitsTab()
                    2 -> DisplayTab()
                    3 -> NavigationTab()
                    4 -> LocationTab()
                    5 -> MediaTab()
                    6 -> AppsTab()
                    7 -> TripsTab()
                    8 -> DiagnosticsTab()
                    else -> SystemTab()
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

// ============================================================ building blocks

@Composable
internal fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Caption(tr(title).uppercase(), Modifier.padding(start = 12.dp, top = 16.dp, bottom = 4.dp), color = OhmTheme.colors.textHint)
    Column(content = content)
}

@Composable
internal fun Toggle(pref: Pref<Boolean>, title: String, subtitle: String? = null) {
    val v by pref.state.collectAsState()
    SettingRow(tr(title), subtitle?.let { tr(it) }, onClick = { pref.set(!v) }) { OhmSwitch(v) { pref.set(it) } }
}

@Composable
internal fun <T> Choice(pref: Pref<T>, title: String, options: List<Pair<T, String>>) = PrefChoice(pref, title, options, Modifier.padding(horizontal = 12.dp))

@Composable
internal fun Note(text: String) = Text(tr(text), style = OhmTheme.type.caption, color = OhmTheme.colors.textSecondary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))

@Composable
internal fun Mono(k: String, v: String) {
    val c = OhmTheme.colors
    Row(Modifier.padding(vertical = 1.dp)) {
        Text(k, style = OhmTheme.type.caption.copy(fontFamily = FontFamily.Monospace), color = c.textSecondary, modifier = Modifier.width(170.dp))
        Text(v, style = OhmTheme.type.caption.copy(fontFamily = FontFamily.Monospace), color = c.textPrimary)
    }
}

@Composable
internal fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(OhmTheme.colors.surface).padding(12.dp), content = content)
}

@Composable
private fun CapRow(name: String, ok: Boolean, detail: String? = null) {
    val c = OhmTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Cancel, null, tint = if (ok) Accent.success else c.iconMuted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(tr(name), style = OhmTheme.type.body, color = c.textPrimary, modifier = Modifier.weight(1f))
        if (detail != null) Caption(detail)
    }
}

/** Re-composes every second (for "x s ago" values). */
@Composable
internal fun tick(): Long {
    var t by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); t = SystemClock.elapsedRealtime() } }
    return t
}

internal fun ago(ms: Long): String = when {
    ms < 1500 -> "now"
    ms < 60_000 -> "${ms / 1000} s"
    else -> "${ms / 60_000} min"
}

@Composable
internal fun NumberField(value: Float, placeholder: String, onValue: (Float) -> Unit) {
    var text by remember(value) { mutableStateOf(if (value > 0) value.toString() else "") }
    OhmTextField(text, { t -> text = t.replace(',', '.'); t.replace(',', '.').toFloatOrNull()?.let(onValue) ?: if (t.isBlank()) onValue(0f) else Unit },
        placeholder, Modifier.width(220.dp).padding(horizontal = 12.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}

// ============================================================ Vehicle (profile-scoped)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VehicleTab() {
    val c = OhmTheme.colors
    val vehicles by Ohm.profiles.list.collectAsState()
    val active by Ohm.profiles.active.collectAsState()
    val name by Ohm.prefs.vehicleName.state.collectAsState()
    val caps by Ohm.caps.caps.collectAsState()
    val showAll by Ohm.prefs.showAllOptions.state.collectAsState()
    val src by Ohm.prefs.carSource.state.collectAsState()

    Section("Vehicles") {
        FlowRow(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (v in vehicles) Pill(v.name, v.id == active, icon = Icons.Rounded.DirectionsCar) { Ohm.profiles.switchTo(v.id) }
            Pill(tr("Add vehicle"), false, icon = Icons.Rounded.Add) { Ohm.profiles.add(trNow("Car") + " ${vehicles.size + 1}") }
        }
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OhmTextField(name, { Ohm.profiles.rename(it) }, tr("Vehicle name"), Modifier.widthIn(max = 360.dp).weight(1f))
            if (vehicles.size > 1) IconBtn(Icons.Rounded.Delete, tr("Delete vehicle"), tint = Accent.danger) { Ohm.profiles.delete(active) }
        }
        Note("Everything on this tab belongs to the selected vehicle. Units, theme and language are shared.")
    }
    Section("Model") {
        Choice(Ohm.prefs.carBody, "Body", listOf("hatch" to "Hatchback", "estate" to "Estate", "coupe" to "Coupé"))
        Text(tr("Paint"), style = OhmTheme.type.body, color = c.textPrimary, modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp))
        Box(Modifier.padding(horizontal = 12.dp)) { PaintPicker() }
        val color by Ohm.prefs.carColor.state.collectAsState()
        Caption(tr(CarPaints.getOrElse(color) { CarPaints[0] }.first), Modifier.padding(start = 12.dp, top = 4.dp))
        Choice(Ohm.prefs.rhd, "Driving position", listOf(false to "Left-hand drive", true to "Right-hand drive"))
    }
    Section("Car data") {
        Choice(Ohm.prefs.carSource, "Data source", carSourceOptions(caps.canPossible || showAll || src == "vendor"))
        if (!caps.canPossible) Note("No CAN / MCU service detected on this unit. Enable \"Show unsupported options\" in Diagnostics to configure it anyway.")
        if (src == "vendor") {
            val actions by Ohm.prefs.vendorActions.state.collectAsState()
            var draft by remember(actions) { mutableStateOf(actions) }
            Spacer(Modifier.height(8.dp))
            OhmTextField(draft, { draft = it }, tr("Vendor broadcast actions, comma-separated"), Modifier.fillMaxWidth().padding(horizontal = 12.dp))
            Row(Modifier.padding(12.dp)) { SecondaryButton(tr("Save actions")) { Ohm.prefs.vendorActions.set(draft.trim()) } }
        }
        val cons by Ohm.prefs.consumption.state.collectAsState()
        val unit by Ohm.prefs.fuelUnit.state.collectAsState()
        SettingRow(tr("Typical consumption (L/100 km)"), if (cons > 0) Fmt.consumption(cons.toDouble(), unit) + " · " + tr("used to estimate trip fuel and cost") else tr("Unknown — trips won't show fuel estimates"))
        NumberField(cons, "e.g. 6.2") { Ohm.prefs.consumption.set(it.coerceIn(0f, 40f)) }
    }
    ObdSection()
    if (caps.camera || src == "demo" || showAll) ReverseSection(caps.cameraApps)
    else Section("Reverse camera") { Note("No camera app detected — your unit's own reverse camera keeps working as usual.") }
}

@Composable
private fun ReverseSection(cameraApps: List<String>) {
    val apps by Ohm.apps.apps.collectAsState()
    val cam by Ohm.prefs.cameraApp.state.collectAsState()
    val auto by Ohm.prefs.cameraAuto.state.collectAsState()
    val trigger by Ohm.prefs.cameraTrigger.state.collectAsState()
    Section("Reverse camera") {
        Toggle(Ohm.prefs.cameraAuto, "Ohm reacts to reverse gear", "Show the camera when reversing")
        Note("This only changes what Ohm does. The head unit's own reverse-camera switching is never disabled or overridden.")
        if (!auto) return@Section
        var open by remember { mutableStateOf(false) }
        SettingRow(tr("Camera app"), apps.firstOrNull { it.pkg == cam }?.label ?: tr("None — Ohm shows its own view"), onClick = { open = !open })
        if (open) Panel {
            SettingRow(tr("None — Ohm shows its own view"), onClick = { Ohm.prefs.cameraApp.set(""); open = false })
            val sorted = apps.sortedByDescending { it.pkg in cameraApps }
            for (a in sorted) SettingRow(a.label + if (a.pkg in cameraApps) "  ★" else "", onClick = { Ohm.prefs.cameraApp.set(a.pkg); open = false })
        }
        Choice(Ohm.prefs.cameraTrigger, "Trigger", listOf("can" to "CAN gear", "mcu" to "MCU broadcast", "broadcast" to "Android broadcast"))
        if (trigger != "can") {
            val action by Ohm.prefs.cameraAction.state.collectAsState()
            val extra by Ohm.prefs.cameraExtra.state.collectAsState()
            OhmTextField(action, { Ohm.prefs.cameraAction.set(it.trim()) }, tr("Broadcast action (see Diagnostics › CAN)"), Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
            OhmTextField(extra, { Ohm.prefs.cameraExtra.set(it.trim()) }, tr("Extra meaning \"in reverse\", e.g. state=1 (optional)"), Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
        }
        Toggle(Ohm.prefs.cameraReturn, "Return to Ohm after reversing")
    }
}

// ============================================================ Units (global)

@Composable
private fun UnitsTab() {
    val cur by Ohm.prefs.currency.state.collectAsState()
    val price by Ohm.prefs.fuelPrice.state.collectAsState()
    Section("Regional preferences") {
        Choice(Ohm.prefs.distanceKm, "Distance", listOf(true to "km", false to "mi"))
        Choice(Ohm.prefs.speedKmh, "Speed", listOf(true to "km/h", false to "mph"))
        Choice(Ohm.prefs.tempC, "Temperature", listOf(true to "°C", false to "°F"))
        Choice(Ohm.prefs.fuelUnit, "Fuel consumption", listOf("l100" to "L/100 km", "kml" to "km/L", "mpgus" to "MPG (US)", "mpguk" to "MPG (UK)"))
        Choice(Ohm.prefs.pressureUnit, "Pressure", listOf("bar" to "bar", "psi" to "PSI", "kpa" to "kPa"))
        Note("Pressure is used for tyre data when a car source provides it.")
    }
    Section("Currency & fuel price") {
        Box(Modifier.padding(12.dp)) { CurrencyPicker() }
        SettingRow(tr("Fuel price per litre"), if (price > 0) Fmt.money(price.toDouble(), cur) else tr("Not set"))
        NumberField(price, "e.g. 1.79") { Ohm.prefs.fuelPrice.set(it.coerceIn(0f, 100f)) }
    }
}

// ============================================================ Display (global)

@Composable
private fun DisplayTab() {
    val reason by Ohm.dayNight.reason.collectAsState()
    val dark by Ohm.dayNight.dark.collectAsState()
    val layout by Ohm.prefs.layout.state.collectAsState()
    Section("Theme") {
        Choice(Ohm.prefs.themeMode, "Appearance", listOf("auto_lights" to "Auto (headlights)", "auto_time" to "Auto (sunset)", "light" to "Light", "dark" to "Dark"))
        Caption(tr("Now") + ": " + tr(if (dark) "Dark" else "Light") + " · " + tr(reason), Modifier.padding(start = 12.dp))
        Toggle(Ohm.prefs.systemDarkSync, "Also switch Android dark mode", "So Google Maps, Spotify etc. follow the headlights (may need extra permission on some units)")
        Text(tr("Accent colour"), style = OhmTheme.type.body, color = OhmTheme.colors.textPrimary, modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp))
        Box(Modifier.padding(horizontal = 12.dp)) { AccentPicker() }
        Choice(Ohm.prefs.motion, "Animations", listOf("full" to "Full", "reduced" to "Reduced", "off" to "Off"))
    }
    Section("Layout") {
        Choice(Ohm.prefs.layout, "Dashboard", listOf("standard" to "Car + map", "map" to "Map focus"))
        if (layout == "standard") {
            val f by Ohm.prefs.panelFraction.state.collectAsState()
            SettingRow(tr("Car panel width"), "${(f * 100).toInt()} %") {}
            OhmSlider(f, { Ohm.prefs.panelFraction.set(it) }, Modifier.padding(horizontal = 12.dp), range = 0.28f..0.45f)
        }
        Toggle(Ohm.prefs.hideSystemBars, "Hide Android status & navigation bars")
    }
    Section("Widgets") {
        Toggle(Ohm.prefs.showMusicWidget, "Music widget")
        Toggle(Ohm.prefs.showSpeedLimit, "Speed-limit sign")
        Toggle(Ohm.prefs.showWeather, "Weather in the top bar")
        Toggle(Ohm.prefs.showCarButtons, "Car buttons (lock, trunk, hood)")
        Toggle(Ohm.prefs.contextualDock, "Contextual dock buttons", "End route, voice, call controls appear in a fixed spot when relevant")
    }
    LockoutSection()
    Section("Performance") {
        Choice(Ohm.prefs.lowPower, "Low-power mode", listOf("auto" to "Auto", "on" to "On", "off" to "Off"))
        Note("Removes blur, glows and shadows; map limited to 30 fps")
    }
}

// ============================================================ Navigation (global)

@Composable
private fun NavigationTab() {
    val ctx = LocalContext.current
    val available = remember { NavProviders.available(ctx) }
    Section("Navigation provider") {
        Choice(Ohm.prefs.navApp, "Navigate with", available.map { it.id to it.label })
        val missing = NavProviders.all.filter { it !in available }
        if (missing.isNotEmpty()) Note(trNow("Not installed") + ": " + missing.joinToString { it.label })
        Note("If the chosen app isn't available, Ohm falls back to its built-in navigation.")
    }
    Section("Map") {
        Choice(Ohm.prefs.mapStyle, "Map style", listOf("auto" to "Automatic", "light" to "Light", "dark" to "Dark", "satellite" to "Satellite"))
        Toggle(Ohm.prefs.headingUp, "Heading up while driving")
        Toggle(Ohm.prefs.buildings3d, "3D buildings")
    }
    Section("Built-in navigation") {
        Toggle(Ohm.prefs.voice, "Voice guidance")
        Toggle(Ohm.prefs.speedCameras, "Speed camera alerts")
        Toggle(Ohm.prefs.avoidTolls, "Avoid tolls")
        Toggle(Ohm.prefs.avoidHighways, "Avoid highways")
    }
    Section("Saved places") {
        SettingRow(tr("Clear Home & Work"), onClick = { Ohm.prefs.home.set(""); Ohm.prefs.work.set("") })
        SettingRow(tr("Clear recent destinations"), onClick = { Ohm.prefs.recentPlaces.set(emptyList()) })
    }
    Note("Maps © OpenStreetMap contributors, © CARTO. Routing: OSRM demo server. Search: Photon (komoot).")
}

// ============================================================ Location

@Composable
private fun LocationTab() {
    val now = tick()
    val fixes by Ohm.location.fixes.collectAsState()
    val status by Ohm.location.status.collectAsState()
    val companion by Ohm.location.companion.collectAsState()
    val code by Ohm.prefs.companionCode.state.collectAsState()
    val port by Ohm.prefs.externalGpsPort.state.collectAsState()
    val km by Ohm.prefs.distanceKm.state.collectAsState()
    val c = OhmTheme.colors
    Section("Location provider") {
        Choice(Ohm.prefs.locationProvider, "Use", listOf("auto" to "Automatic", "unit" to "Head-unit GPS", "companion" to "Phone (Ohm Companion)", "external" to "External GPS"))
        Note("Automatic picks the freshest, most accurate fix. A manual choice falls back to another provider when its data is stale (> 6 s) or inaccurate.")
        Panel {
            val ch = status.chosen
            Text(if (ch == null) tr("No location fix") else tr("Using") + ": " + providerName(ch.provider) + if (status.fallback) " (" + trNow("fallback") + ")" else "",
                style = OhmTheme.type.bodyStrong, color = if (ch == null) Accent.warning else c.textPrimary)
            for (p in com.ohm.launcher.loc.ProviderNames) {
                val f = fixes[p]
                Mono(providerName(p), if (f == null) "—" else "${f.quality} · ±${f.accuracyM?.toInt() ?: "?"} m · ${ago(now - f.receivedAt)}")
            }
        }
    }
    Section("Ohm Companion (phone)") {
        Note("Install the Ohm Companion app on your phone, join the same Wi-Fi or phone hotspot as the head unit, and enter this pairing code:")
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(code, style = OhmTheme.type.display.copy(fontSize = 40.sp, letterSpacing = 8.sp), color = Accent.accent)
            Spacer(Modifier.width(16.dp))
            SecondaryButton(tr("New code")) { Ohm.prefs.companionCode.set((1000..9999).random().toString()) }
        }
        Panel {
            val cs = companion
            if (cs == null || now - cs.lastSeen > 15_000) Text(tr("Not connected"), style = OhmTheme.type.body, color = c.textSecondary)
            else {
                Mono("device", cs.device)
                Mono("battery", cs.battery?.let { "$it %" } ?: "—")
                Mono("network", cs.network ?: "—")
                Mono("address", cs.address)
                Mono("last seen", ago(now - cs.lastSeen))
            }
            if (status.rejectedPackets > 0) Mono("wrong code", "${status.rejectedPackets} packets ignored")
        }
        Note("Location, speed, heading, phone battery/network and (optionally) notifications are shared over the local network only.")
    }
    Section("External GPS receiver") {
        Note("Any receiver or app that sends NMEA 0183 sentences (RMC/GGA) over UDP to this unit.")
        var p by remember(port) { mutableStateOf(port.toString()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OhmTextField(p, { p = it.filter(Char::isDigit).take(5) }, "UDP port", Modifier.width(180.dp).padding(horizontal = 12.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            SecondaryButton(tr("Apply")) { p.toIntOrNull()?.takeIf { it in 1024..65535 }?.let { Ohm.prefs.externalGpsPort.set(it) } }
        }
    }
    @Suppress("unused") val k = km
}

private fun providerName(p: String) = trNow(when (p) { "unit" -> "Head-unit GPS"; "companion" -> "Phone (Ohm Companion)"; else -> "External GPS" })

// ============================================================ Media (global)

@Composable
private fun MediaTab() {
    val access by Ohm.media.hasAccess.collectAsState()
    val np by Ohm.media.nowPlaying.collectAsState()
    Section("Media source") {
        Choice(Ohm.prefs.mediaMode, "Mode", listOf("auto" to "Automatic", "standalone" to "This device", "phone" to "Phone (Bluetooth)"))
        Note("This device: apps running on the unit (Spotify, YouTube Music…) — full control and in-app search.")
        Note("Phone: a phone connected over Bluetooth (AVRCP) — Ohm shows what's playing and controls it; the phone handles accounts and playback.")
        np?.let { Caption(tr("Now") + ": " + it.appLabel + " · " + tr(when (it.origin) { "phone" -> "Phone (Bluetooth)"; "demo" -> "Demo"; else -> "This device" }), Modifier.padding(12.dp)) }
    }
    AudioSection()
    Section("Access") {
        SettingRow(tr("Notification access"), tr(if (access) "Granted — music apps can be controlled" else "Needed to show and control what's playing"), onClick = { Ohm.media.openAccessSettings() }) {
            Pill(tr(if (access) "Open" else "Allow"), !access) { Ohm.media.openAccessSettings() }
        }
        Toggle(Ohm.prefs.demoMedia, "Demo player when nothing is playing")
        val step by Ohm.prefs.volumeStep.state.collectAsState()
        SettingRow(tr("Volume step"), "$step %") {}
        OhmSlider(step.toFloat(), { Ohm.prefs.volumeStep.set(it.toInt()) }, Modifier.padding(horizontal = 12.dp), range = 1f..20f, steps = 18)
    }
    SpotifySection()
    ReceiverSection()
}

@Composable
private fun SpotifySection() {
    val ui = LocalUi.current
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val auth by Ohm.spotify.auth.state.collectAsState()
    val savedId by Ohm.prefs.spotifyClientId.state.collectAsState()
    val device by Ohm.prefs.spotifyDevice.state.collectAsState()
    var id by remember(savedId) { mutableStateOf(savedId) }
    Section("Spotify") {
        when (val a = auth) {
            is com.ohm.launcher.spotify.AuthState.SignedIn -> {
                SettingRow(tr("Signed in as") + " " + a.name, tr("Spotify Connect, library, search, queue and lyrics")) {
                    Pill(tr("Open"), true) { ui.sheet = Sheet.SPOTIFY }
                }
                SettingRow(tr("Preferred device"), device.ifEmpty { tr("This unit / whatever is active") }, onClick = { ui.sheet = Sheet.SPOTIFY; com.ohm.launcher.ui.spotify.SpNav.go(com.ohm.launcher.ui.spotify.SpPage.Devices) })
                Toggle(Ohm.prefs.spotifyInOhm, "Open Spotify inside Ohm", "The Spotify icon in the dock opens Ohm's Spotify screen instead of the app")
                Toggle(Ohm.prefs.lyrics, "Synced lyrics", "From LRCLIB, a free community lyrics database")
                SettingRow(tr("Sign out of Spotify"), onClick = { Ohm.spotify.signOut() })
            }
            else -> {
                Note("Connect your Spotify account to browse your library, search, pick a Connect device and see lyrics — like the in-car Spotify app. Needs your own free Spotify developer app (Client ID).")
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OhmTextField(id, { id = it.trim() }, "Client ID", Modifier.widthIn(max = 360.dp).weight(1f))
                    Spacer(Modifier.width(8.dp))
                    SecondaryButton(tr("Save")) { Ohm.prefs.spotifyClientId.set(id); Ohm.spotify.auth.onClientIdChanged() }
                }
                Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(tr("Sign in with Spotify")) { if (savedId.isNotBlank()) scope.launch { Ohm.spotify.auth.signIn(ctx) } else ui.sheet = Sheet.SPOTIFY }
                    SecondaryButton(tr("Setup guide")) { ui.sheet = Sheet.SPOTIFY }
                }
                (a as? com.ohm.launcher.spotify.AuthState.Error)?.let { Note(it.message) }
                Note("Redirect URI to register: " + com.ohm.launcher.spotify.SpotifyAuth.REDIRECT)
            }
        }
    }
}

/** Built-in Spotify Connect receiver (librespot). */
@Composable
private fun ReceiverSection() {
    val c = OhmTheme.colors
    val on by Ohm.prefs.librespot.state.collectAsState()
    val status by Ohm.receiver.status.collectAsState()
    val detail by Ohm.receiver.detail.collectAsState()
    val name by Ohm.prefs.librespotName.state.collectAsState()
    Section("Built-in Spotify receiver") {
        Note("Makes this head unit a Spotify Connect speaker by itself, so music plays through the car without the Spotify app. Powered by librespot, an unofficial open-source client: it isn't approved by Spotify, needs Premium, and may stop working when Spotify changes things. Your account is used at your own risk.")
        if (!Ohm.receiver.available) { Note("librespot isn't included in this build."); return@Section }
        Toggle(Ohm.prefs.librespot, "Enable built-in receiver")
        if (on) {
            Panel {
                Text(tr(status.name.lowercase().replaceFirstChar { it.uppercase() }.replace('_', ' ')), style = OhmTheme.type.bodyStrong, color = when (status) {
                    com.ohm.launcher.spotify.Receiver.Status.READY, com.ohm.launcher.spotify.Receiver.Status.PLAYING -> Accent.success
                    com.ohm.launcher.spotify.Receiver.Status.ERROR -> Accent.danger
                    else -> c.textPrimary
                })
                if (detail.isNotEmpty()) Caption(detail)
            }
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!Ohm.receiver.hasCredentials) PrimaryButton(tr("Sign in for the receiver")) { Ohm.receiver.signIn() }
                else SecondaryButton(tr("Sign out receiver")) { Ohm.receiver.signOut() }
                SecondaryButton(tr("Restart")) { Ohm.receiver.start() }
            }
            Note("Or, without signing in: open Spotify on a phone on the same Wi-Fi/hotspot and pick the device below in its device list — the login is handed over and remembered.")
            var draft by remember(name) { mutableStateOf(name) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OhmTextField(draft, { draft = it }, tr("Device name"), Modifier.widthIn(max = 300.dp).padding(horizontal = 12.dp))
                SecondaryButton(tr("Apply")) { Ohm.prefs.librespotName.set(draft.trim().ifEmpty { "Ohm (car)" }); Ohm.receiver.start() }
            }
            Choice(Ohm.prefs.librespotBitrate, "Quality", listOf(96 to "Low (96 kbps)", 160 to "Normal (160 kbps)", 320 to "High (320 kbps)"))
        }
    }
}

// ============================================================ Apps

@Composable
private fun AppsTab() {
    val c = OhmTheme.colors
    val apps by Ohm.apps.apps.collectAsState()
    val dock by Ohm.prefs.dockApps.state.collectAsState()
    val hidden by Ohm.prefs.hiddenApps.state.collectAsState()
    Section("Dock") {
        if (dock.isEmpty()) Note("Pin apps from the app drawer")
        dock.forEachIndexed { i, pkg ->
            val a = apps.firstOrNull { it.pkg == pkg } ?: return@forEachIndexed
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(a, 36); Spacer(Modifier.width(12.dp))
                Text(a.label, style = OhmTheme.type.body, color = c.textPrimary, modifier = Modifier.weight(1f))
                IconBtn(Icons.Rounded.ArrowUpward, tr("Move left"), enabled = i > 0) { Ohm.apps.move(pkg, -1) }
                IconBtn(Icons.Rounded.ArrowDownward, tr("Move right"), enabled = i < dock.size - 1) { Ohm.apps.move(pkg, 1) }
                IconBtn(Icons.Rounded.PushPin, tr("Unpin"), tint = Accent.danger) { Ohm.apps.unpin(pkg) }
            }
        }
    }
    LockoutAppsSection()
    Section("Hidden apps") {
        if (hidden.isEmpty()) Note("None — hide apps from the drawer's edit mode")
        for (pkg in hidden) {
            val a = apps.firstOrNull { it.pkg == pkg }
            SettingRow(a?.label ?: pkg) { IconBtn(Icons.Rounded.Visibility, tr("Show")) { Ohm.apps.setHidden(pkg, false) } }
        }
    }
}

// ============================================================ Trips

@Composable
private fun TripsTab() {
    val cur by Ohm.trips.current.collectAsState()
    val history by Ohm.trips.history.collectAsState()
    Section("Current trip") {
        val t = cur
        if (t == null) Note("No trip in progress — a trip starts automatically when the car moves.")
        else TripCard(t, live = true)
    }
    Section("History") {
        if (history.isEmpty()) Note("No trips yet")
        for (t in history.take(30)) TripCard(t, live = false)
        if (history.isNotEmpty()) Row(Modifier.padding(12.dp)) { SecondaryButton(tr("Clear history"), icon = Icons.Rounded.Delete) { Ohm.trips.clear() } }
    }
    Note("Fuel and cost are estimates from the vehicle's typical consumption and your fuel price — this car doesn't report real fuel data yet.")
}

@Composable
private fun TripCard(t: Trip, live: Boolean) {
    val c = OhmTheme.colors
    val km by Ohm.prefs.distanceKm.state.collectAsState()
    val kmh by Ohm.prefs.speedKmh.state.collectAsState()
    val cur by Ohm.prefs.currency.state.collectAsState()
    val fuelUnit by Ohm.prefs.fuelUnit.state.collectAsState()
    val df = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (live) tr("In progress") else df.format(Date(t.start)), style = OhmTheme.type.bodyStrong, color = c.textPrimary, modifier = Modifier.weight(1f))
            Caption(t.vehicle)
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Stat(tr("Distance"), Fmt.distance(t.distanceM, km))
            Stat(tr("Time"), Fmt.duration(t.durationS))
            Stat(tr("Average"), "${Fmt.speed(t.avgKmh.toFloat(), kmh)} ${if (kmh) "km/h" else "mph"}")
            Stat(tr("Max"), "${Fmt.speed(t.maxKmh, kmh)} ${if (kmh) "km/h" else "mph"}")
            t.fuelL?.let { f ->
                Stat(tr(if (t.fuelMeasured) "Fuel" else "Fuel (est.)"), Fmt.volume(f, km))
                if (t.distanceM > 1000) Stat(tr("Consumption"), Fmt.consumption(f / (t.distanceM / 100_000), fuelUnit))
            }
            t.cost?.let { Stat(tr(if (t.fuelMeasured) "Cost" else "Cost (est.)"), Fmt.money(it, cur)) }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Caption(label)
        Text(value, style = OhmTheme.type.bodyStrong, color = OhmTheme.colors.textPrimary)
    }
}

// ============================================================ Diagnostics

@Composable
private fun DiagnosticsTab() {
    val ctx = LocalContext.current
    val now = tick()
    val caps by Ohm.caps.caps.collectAsState()
    val loc by Ohm.location.status.collectAsState()
    val raw by Ohm.car.raw.collectAsState()
    val src by Ohm.car.sourceId.collectAsState()
    val car by Ohm.car.state.collectAsState()
    val lastCan by Ohm.car.lastVendorMessageAt.collectAsState()
    val sessions by Ohm.media.sessions.collectAsState()
    val frame by Perf.frameMs.collectAsState()
    val slow by Perf.slow.collectAsState()
    val logs by OhmLog.lines.collectAsState()
    val wall = remember(now) { System.currentTimeMillis() }

    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton(tr("Re-scan hardware")) { Ohm.caps.refresh() }
        SecondaryButton(tr("Share report")) {
            val report = buildString {
                appendLine("Ohm diagnostics"); appendLine(caps.toString()); appendLine("location: $loc"); appendLine("car: $src $car")
                appendLine("sessions: $sessions"); appendLine("frame ms: $frame"); appendLine(); append(OhmLog.text())
            }
            runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report), "Ohm diagnostics").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
    Toggle(Ohm.prefs.showAllOptions, "Show unsupported options", "Offer settings even for hardware Ohm didn't detect")

    Section("Capabilities") {
        CapRow("GPS hardware", caps.gpsHardware)
        CapRow("Location permission", caps.locationPermission)
        CapRow("Bluetooth", caps.bluetooth)
        CapRow("CAN / MCU service", caps.canPossible, caps.vendorPackages.size.takeIf { it > 0 }?.let { "$it pkg" })
        CapRow("CAN data received", caps.canReceiving)
        CapRow("Camera app", caps.camera, caps.cameraApps.size.takeIf { it > 0 }?.toString())
        CapRow("Media control (notification access)", caps.mediaAccess)
        CapRow("Google Maps", caps.googleMaps)
        CapRow("Waze", caps.waze)
        CapRow("Ohm Companion seen", caps.companionSeen)
        CapRow("External GPS seen", caps.externalGpsSeen)
    }
    Section("GPS") {
        Panel {
            Mono("satellites", "${loc.satsUsed} used / ${loc.satsVisible} visible")
            Mono("chosen", loc.chosen?.let { "${it.provider} ${it.quality} ±${it.accuracyM?.toInt() ?: "?"} m, ${ago(now - it.receivedAt)}" } ?: "none")
            Mono("fallback", loc.fallback.toString())
            loc.chosen?.let { Mono("position", "%.5f, %.5f".format(it.lat, it.lon)) }
            if (loc.chosen?.quality == Quality.STALE) Mono("warning", "fix is stale")
        }
    }
    Section("CAN / MCU") {
        Panel {
            Mono("source", src)
            Mono("last message", if (lastCan == 0L) "never" else ago(wall - lastCan))
            Mono("vendor packages", caps.vendorPackages.joinToString().ifEmpty { "none found" })
            for ((k, v) in raw.toSortedMap()) Mono(k, v)
        }
        val demo = Ohm.car.demo
        if (src == "demo" && demo != null) {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (g in Gear.entries) Pill(g.name, car.gear == g) { demo.setGear(g) }
            }
            SettingRow(tr("Headlights")) { OhmSwitch(car.headlights == true) { demo.setHeadlights(it) } }
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(tr("Front left door"), car.doors.fl) { demo.toggleDoor("fl") }
                Pill(tr("Front right door"), car.doors.fr) { demo.toggleDoor("fr") }
                Pill(tr("Rear left door"), car.doors.rl) { demo.toggleDoor("rl") }
                Pill(tr("Rear right door"), car.doors.rr) { demo.toggleDoor("rr") }
            }
            Note("Doors, trunk and hood only open in P")
        }
    }
    Section("Media sessions") {
        Panel {
            if (sessions.isEmpty()) Mono("sessions", if (caps.mediaAccess) "none active" else "no notification access")
            for (s in sessions) Mono(s.label.take(22), "${s.state}${if (s.phone) " · bluetooth" else ""}")
        }
    }
    Section("Rendering") {
        Panel {
            Mono("frame time", if (frame > 0) "%.1f ms (~%d fps)".format(frame, (1000 / frame.coerceAtLeast(1.0)).toInt().coerceAtMost(60)) else "—")
            Mono("slow frames", slow.toString())
            Mono("low-power", Ohm.prefs.lowPower.value)
        }
    }
    Section("Log") {
        Panel {
            for (l in logs.takeLast(80).reversed()) Text(l, style = OhmTheme.type.caption.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp), color = OhmTheme.colors.textSecondary)
        }
    }
}

// ============================================================ System

@Composable
private fun SystemTab() {
    val ctx = LocalContext.current
    val ui = LocalUi.current
    Section("Language") { Choice(Ohm.prefs.language, "Language", listOf("en" to "English", "pt" to "Português")) }
    UpdatesSection()
    Section("Launcher") {
        SettingRow(tr("Set Ohm as the Home app"), tr("Choose Ohm in the Android home-app picker"), onClick = { Ohm.apps.openSettings(Settings.ACTION_HOME_SETTINGS) })
        SettingRow(tr("Location permission"), tr(if (Ohm.nav.hasLocationPermission()) "Granted" else "Needed for GPS position and weather"), onClick = { (ctx as? MainActivity)?.requestLocation() })
        SettingRow(tr("Run quick setup again"), onClick = { ui.sheet = Sheet.NONE; Ohm.prefs.onboardingDone.set(false) })
        SettingRow(tr("Android settings"), onClick = { Ohm.apps.openSettings(Settings.ACTION_SETTINGS) })
        SettingRow(tr("Screen cleaning mode"), onClick = { ui.sheet = Sheet.NONE; ui.cleanMode = true })
    }
    Section("Demo events") {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(tr("Incoming call"), false) { ui.sheet = Sheet.NONE; Ohm.banners.demoCall() }
            Pill(tr("New message"), false) { ui.sheet = Sheet.NONE; Ohm.banners.demoMessage() }
            Pill(tr("Reverse gear"), false) { ui.sheet = Sheet.NONE; Ohm.car.demo?.setGear(Gear.R) }
        }
    }
    Section("About") {
        SettingRow("Ohm 0.6.1", tr("Tesla-style launcher for Android head units. Not affiliated with Tesla, Inc."))
        Note("Font: Inter (SIL Open Font License). Weather: Open-Meteo.")
    }
}
