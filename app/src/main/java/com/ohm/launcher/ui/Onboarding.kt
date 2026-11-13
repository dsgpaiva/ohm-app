package com.ohm.launcher.ui

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ohm.launcher.MainActivity
import com.ohm.launcher.Ohm
import com.ohm.launcher.car.Car3DView
import com.ohm.launcher.car.CarPaints
import com.ohm.launcher.car.CarState
import com.ohm.launcher.car.Gear
import com.ohm.launcher.core.Pref
import com.ohm.launcher.nav.NavProviders
import com.ohm.launcher.ui.theme.Accent
import com.ohm.launcher.ui.theme.AccentChoices
import com.ohm.launcher.ui.theme.LocalLowPower
import com.ohm.launcher.ui.theme.Motion
import com.ohm.launcher.ui.theme.OhmTheme

val Currencies = listOf("EUR", "USD", "GBP", "BRL", "CHF", "JPY", "AUD", "CAD")

/** Choice row bound to a preference. */
@Composable
fun <T> PrefChoice(pref: Pref<T>, title: String, options: List<Pair<T, String>>, modifier: Modifier = Modifier) {
    val v by pref.state.collectAsState()
    Column(modifier.padding(vertical = 8.dp)) {
        Text(tr(title), style = OhmTheme.type.body, color = OhmTheme.colors.textPrimary)
        Spacer(Modifier.height(8.dp))
        Segmented(options.map { it.first to tr(it.second) }, v, { pref.set(it) })
    }
}

@Composable
fun PaintPicker() {
    val c = OhmTheme.colors
    val color by Ohm.prefs.carColor.state.collectAsState()
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CarPaints.forEachIndexed { i, (_, col) ->
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(col)
                    .border(if (i == color) 3.dp else 1.dp, if (i == color) Accent.accent else c.border, CircleShape)
                    .clickable { Ohm.prefs.carColor.set(i) },
            )
        }
    }
}

@Composable
fun AccentPicker() {
    val sel by Ohm.prefs.accent.state.collectAsState()
    val c = OhmTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AccentChoices.forEachIndexed { i, (_, col) ->
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(col)
                    .border(if (i == sel) 3.dp else 0.dp, if (i == sel) c.textPrimary else Color.Transparent, CircleShape)
                    .clickable { Ohm.prefs.accent.set(i) },
                contentAlignment = Alignment.Center,
            ) { if (i == sel) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
        }
    }
}

@Composable
fun CurrencyPicker() {
    val cur by Ohm.prefs.currency.state.collectAsState()
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (code in Currencies) Pill(code, code == cur) { Ohm.prefs.currency.set(code) }
        var custom by remember { mutableStateOf(if (cur in Currencies) "" else cur) }
        OhmTextField(custom, { v -> custom = v.uppercase().take(3); if (custom.length == 3) Ohm.prefs.currency.set(custom) }, tr("Other (ISO code)"), Modifier.width(170.dp))
    }
}

/** First-launch setup (SPEC-ideas §1.1). Global preferences first, then the first vehicle. */
@Composable
fun Onboarding(onDone: () -> Unit) {
    val c = OhmTheme.colors
    var step by remember { mutableIntStateOf(0) }
    val steps = listOf("Welcome", "Units", "Vehicle", "Appearance", "Navigation", "Permissions")
    val last = steps.size - 1

    Row(Modifier.fillMaxSize().background(c.bg)) {
        // left: step list
        Column(Modifier.width(260.dp).fillMaxSize().background(c.panel).padding(28.dp)) {
            Text("Ohm", style = OhmTheme.type.display.copy(fontSize = 40.sp), color = c.textPrimary)
            Caption(tr("Quick setup"), Modifier.padding(bottom = 28.dp))
            steps.forEachIndexed { i, s ->
                Row(Modifier.padding(vertical = 8.dp).clickable { step = i }, verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(if (i <= step) Accent.accent else c.surfaceElevated), contentAlignment = Alignment.Center) {
                        if (i < step) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        else Text("${i + 1}", style = OhmTheme.type.label, color = if (i == step) Color.White else c.textSecondary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(tr(s), style = OhmTheme.type.bodyStrong, color = if (i == step) c.textPrimary else c.textSecondary)
                }
            }
            Spacer(Modifier.weight(1f))
            Caption(tr("You can change everything later in Controls."))
        }
        // right: content
        Column(Modifier.weight(1f).fillMaxSize().padding(horizontal = 40.dp, vertical = 28.dp)) {
            AnimatedContent(step, Modifier.weight(1f), transitionSpec = { fadeIn(tween(Motion.dur(200))) togetherWith fadeOut(tween(Motion.dur(150))) }, label = "step") { s ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    when (s) {
                        0 -> StepWelcome()
                        1 -> StepUnits()
                        2 -> StepVehicle()
                        3 -> StepAppearance()
                        4 -> StepNavigation()
                        else -> StepPermissions()
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (step > 0) SecondaryButton(tr("Back")) { step-- }
                Spacer(Modifier.weight(1f))
                if (step < last) {
                    Text(tr("Skip setup"), style = OhmTheme.type.label, color = c.textSecondary, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onDone() }.padding(12.dp))
                    Spacer(Modifier.width(12.dp))
                    PrimaryButton(tr("Next"), Modifier.widthIn(min = 140.dp)) { step++ }
                } else PrimaryButton(tr("Start driving"), Modifier.widthIn(min = 180.dp)) { onDone() }
            }
        }
    }
}

@Composable
private fun StepTitle(title: String, subtitle: String) {
    Text(tr(title), style = OhmTheme.type.display, color = OhmTheme.colors.textPrimary)
    Text(tr(subtitle), style = OhmTheme.type.body, color = OhmTheme.colors.textSecondary, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
}

@Composable
private fun ColumnScope.StepWelcome() {
    val dark = OhmTheme.colors.isDark
    val body by Ohm.prefs.carBody.state.collectAsState()
    val paint by Ohm.prefs.carColor.state.collectAsState()
    StepTitle("Welcome to Ohm", "A calm, minimal home screen for your car. Let's set a few things up.")
    PrefChoice(Ohm.prefs.language, "Language", listOf("en" to "English", "pt" to "Português"))
    Car3DView(Modifier.fillMaxWidth().height(260.dp), CarState(gear = Gear.P, headlights = dark), body, CarPaints.getOrElse(paint) { CarPaints[0] }.second, dark, LocalLowPower.current)
}

@Composable
private fun StepUnits() {
    StepTitle("Units", "Regional preferences — these apply to every vehicle.")
    PrefChoice(Ohm.prefs.distanceKm, "Distance", listOf(true to "km", false to "mi"))
    PrefChoice(Ohm.prefs.speedKmh, "Speed", listOf(true to "km/h", false to "mph"))
    PrefChoice(Ohm.prefs.tempC, "Temperature", listOf(true to "°C", false to "°F"))
    PrefChoice(Ohm.prefs.fuelUnit, "Fuel consumption", listOf("l100" to "L/100 km", "kml" to "km/L", "mpgus" to "MPG (US)", "mpguk" to "MPG (UK)"))
    PrefChoice(Ohm.prefs.pressureUnit, "Pressure", listOf("bar" to "bar", "psi" to "PSI", "kpa" to "kPa"))
    Text(tr("Currency"), style = OhmTheme.type.body, color = OhmTheme.colors.textPrimary, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
    CurrencyPicker()
}

@Composable
private fun StepVehicle() {
    val name by Ohm.prefs.vehicleName.state.collectAsState()
    val caps by Ohm.caps.caps.collectAsState()
    val showAll by Ohm.prefs.showAllOptions.state.collectAsState()
    StepTitle("Your vehicle", "Vehicle settings are saved per car, so you can add more cars later.")
    OhmTextField(name, { Ohm.prefs.vehicleName.set(it) }, tr("Vehicle name"), Modifier.widthIn(max = 420.dp).fillMaxWidth())
    PrefChoice(Ohm.prefs.carBody, "Body", listOf("hatch" to "Hatchback", "estate" to "Estate", "coupe" to "Coupé"))
    Text(tr("Paint"), style = OhmTheme.type.body, color = OhmTheme.colors.textPrimary, modifier = Modifier.padding(vertical = 8.dp))
    PaintPicker()
    PrefChoice(Ohm.prefs.rhd, "Driving position", listOf(false to "Left-hand drive", true to "Right-hand drive"))
    PrefChoice(Ohm.prefs.carSource, "Car data", carSourceOptions(caps.canPossible || showAll))
    if (!caps.canPossible) Caption(tr("No CAN / MCU service detected on this unit — using demo or GPS only."))
}

fun carSourceOptions(canAvailable: Boolean) = buildList {
    add("demo" to "Demo")
    if (canAvailable) add("vendor" to "Head unit (CAN)")
    add("none" to "None (GPS only)")
}

@Composable
private fun StepAppearance() {
    StepTitle("Appearance", "Automatic follows your headlights (or sunset when the car can't tell).")
    PrefChoice(Ohm.prefs.themeMode, "Theme", listOf("auto_lights" to "Automatic", "light" to "Light", "dark" to "Dark"))
    Text(tr("Accent colour"), style = OhmTheme.type.body, color = OhmTheme.colors.textPrimary, modifier = Modifier.padding(vertical = 8.dp))
    AccentPicker()
}

@Composable
private fun StepNavigation() {
    val ctx = LocalContext.current
    StepTitle("Navigation", "Which app should guide you? Built-in shows directions right on Ohm's map.")
    val available = remember { NavProviders.available(ctx) }
    PrefChoice(Ohm.prefs.navApp, "Navigation provider", available.map { it.id to it.label })
    val missing = NavProviders.all.filter { it !in available }
    if (missing.isNotEmpty()) Caption(tr("Not installed") + ": " + missing.joinToString { it.label })
}

@Composable
private fun StepPermissions() {
    val ctx = LocalContext.current
    val access by Ohm.media.hasAccess.collectAsState()
    StepTitle("Permissions", "Each one unlocks a feature. All optional.")
    PermRow(Icons.Rounded.LocationOn, "Location", "Your position on the map, weather and trips", Ohm.nav.hasLocationPermission()) { (ctx as? MainActivity)?.requestLocation() }
    PermRow(Icons.Rounded.MusicNote, "Notification access", "Show and control Spotify, YouTube Music, Bluetooth audio…", access) { Ohm.media.openAccessSettings() }
    PermRow(Icons.Rounded.Home, "Home app", "Open Ohm when the unit starts and on the Home button", false) { Ohm.apps.openSettings(Settings.ACTION_HOME_SETTINGS) }
}

@Composable
private fun PermRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String, granted: Boolean, onClick: () -> Unit) {
    val c = OhmTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(14.dp)).background(c.surface).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Accent.accent, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(tr(title), style = OhmTheme.type.bodyStrong, color = c.textPrimary)
            Caption(tr(text))
        }
        if (granted) Icon(Icons.Rounded.Check, tr("Granted"), tint = Accent.success) else Pill(tr("Allow"), true, onClick = onClick)
    }
    @Suppress("unused") val k = KeyboardOptions(keyboardType = KeyboardType.Text)
}
